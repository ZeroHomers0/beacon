# Beacon 后端开发规范

适用Go服务端、Cloudflare Workers与客户端之间的HTTP/WebSocket契约。当前代码依据：2026-10-25。完整接口与请求示例仅维护在 [API.md](API.md)，不在README再复制一份。目录与产物规则见 [目录规范](REPOSITORY_GUIDELINES.md)。

## 1. 职责与两种实现

Beacon是通用消息与结构化数据管道。目标服务查询、轮询、文案计算和第三方凭据放外部脚本，不能因一个业务接入向服务端新增专属轮询器或协议字段。

| Go目录 | 职责 |
| --- | --- |
| `server/cmd/beacon/` | 程序入口、配置与启动 |
| `server/internal/httpapi/` | Gin路由、请求解析、鉴权接入、HTTP错误映射和WebSocket |
| `server/internal/service/` | 用例与仓库能力协调 |
| `server/internal/domain/` | 模型、协议版本、字节限制、状态版本规则 |
| `server/internal/store/` | SQLite事务、迁移、配额、保留期与持久化 |
| `server/internal/config/` | 启动配置与版本信息 |

| Workers文件/目录 | 职责 |
| --- | --- |
| `workers/src/api.ts` | HTTP路由和鉴权流程，依赖ports |
| `workers/src/ports.ts` | 存储/传输边界 |
| `workers/src/domain.ts`、`protocol.ts` | 通用模型、限制、协议号 |
| `workers/src/state-version.ts`、`blocks.ts` | 状态转换和结构化校验 |
| `workers/src/states.ts`、`topics.ts` | 数据状态、目录事务及分页同步 |
| `workers/src/store/` | SQLite schema、身份、消息与清理 |
| `workers/src/index.ts` | Durable Object、Socket与定时调度 |

仓储层不能依赖HTTP Request/Response或拥有WebSocket；依赖图必须无环。遵守 `workers/tools/architecture.mjs`，修改模块时同步显式依赖规则，不绕过检查。Go模块与依赖版本以go.mod为准，Workers以package-lock为准，不在规范固定版本后忘记更新代码。

## 2. 协议变更必须双端一致

修改顺序：确认当前代码与API → 修改domain/校验 → 同步Go和Workers → 增加协议/边界样例 → 客户端按能力协商 → 更新API及规范。

- 统一维护 `contract/` 的正例、反例和边界；禁止只让一端接受数字字符串、null、未知字段或跨作用域数据。
- HTTP协议当前1、实时协议2、信封1；数据协议1、结构化schema1、主题目录协议1。更改含义时明确能力字段及旧客户端行为，不能仅提升发布版本号。
- JSON严格校验重复键、未知字段、UTF-8与数值范围；除Bark明确兼容的转换外，不做宽松隐式转换。
- 优先级与UI展示独立，消息事件和状态覆盖独立，目录配置版本和数据revision独立。
- 脚本上传使用HTTP，WebSocket是手机接收通道，不能接受任意客户端业务消息。

## 3. 鉴权、作用域与安全

管理令牌、绑定码、设备推送密钥、应用令牌、接收凭据严格分离。目录创建/修改/删除仅管理端可做；push密钥和应用令牌只能更新已有匹配主题。Bark只用设备密钥，不能让应用令牌绕过原生来源规则。

设备与应用归属必须从凭据解析，不能信任push请求体中的application_id/device_id。主题身份为 `(device, application, topic)`；应用可选但必须属于目标设备。接收接口只能读取当前设备，管理接口用独立凭据且不接受查询参数认证。WebSocket拒绝URL凭据，禁用可能把凭据转发到其他端点的自动重定向。

令牌按现有哈希与常量时间比较实现处理，日志只记路由模板、错误码和聚合数字，不记Bark含密钥路径、Authorization、真实正文或完整请求体。运行时机密通过环境/secret文件注入，不写进样例、数据库转储或提交。手机Bark AES密钥仅保存在手机Keystore，后端只转发密文。

## 4. 普通消息、投递与清理

| 项目 | 当前限制/语义 |
| --- | --- |
| 请求体/标题/正文 | 512 KiB / 1024 UTF-8字节 / 65536 UTF-8字节 |
| 分组/设备名称 | 最多128 UTF-8字节，各字段以domain校验为准 |
| 优先级/TTL | 0–10整数；TTL 0–604800秒，0/省略用7天 |
| HTTPS操作 | 最多1个，拒绝认证信息、控制字符和非HTTPS；不自动执行 |
| 拉取/ACK | 每页或每批最多100，消息默认页25 |
| 单设备待收 | 10000条、64MiB正文预算 |
| 全局消息/数据库有效页 | 100000条 / 256MiB |
| 投递/回执/宽限 | 7天 / 30天 / 30分钟 |
| 已确认历史 | 默认1天，配置1–30天，0按1天处理 |

先事务保存，再安排投递。立即投递失败不能把已接受结果变成假失败导致重发；断线、进程回收、Workers休眠后仍可补收。ACK不是已读，不能提前删除客户端尚未持久化的数据。

普通消息 `Idempotency-Key` 为同发送凭据下1–128个可见非空白ASCII字符；同键同内容七天内返回原结果，不重建已清理消息，不同内容409。指纹包含actions等实际语义。幂等结果、配额计数和消息保存需同事务，失败不得留下半写计数。

容量满时拒绝新消息，不悄悄删除仍有效待收数据。保留SQLite空闲页复用、WAL检查点与 bounded batch 清理；不能因为遍历大表长期占有事务或等待外部网络。

## 5. 数据主题与状态

### 目录及版本

主题目录与最新状态分开持久化。管理员预创建，未上报数据revision为0、config_version为1；第一次数据revision至少1。已有状态/水位仅在内部升级迁移时一次性补齐目录，push请求不能创建目录。

上报先在同一事务检查目录和凭据归属，再检查revision/状态转换、限流、容量、写状态和变更日志。未登记及已硬删除均返回404 `topic_not_found`，不得消耗状态水位或上报配额。创建有request_id幂等和唯一约束，并发不得重复生成主题；更新/删除使用配置版本比较，冲突409。

每设备最多500个目录身份，包括空主题、已清空或过期主题；硬删除释放名额。有效状态负载合计最多8MiB；状态写入每设备300次/分钟、每应用每设备120次/分钟。同版本同内容返回unchanged，不能重复消费写入额度；同版本不同内容或旧版本409。

### 清空与删除

| 操作 | 行为 |
| --- | --- |
| `operation:delete` / admin states clear | 清空最新数据，保留目录、绑定和数据水位；遵守原任务终态约束 |
| TTL到期 | 数据失效并同步移除，保留目录和水位，不伪造更高发送版本 |
| 目录硬删除 | 删除目录、当前数据及水位，不在管理列表保留已删除项；同步删除事件供设备清缓存 |
| 管理员重新创建 | 新生命周期，可从revision1上报；脚本不能自动重建，不提供restore端点 |

删除时清除该身份旧状态变更正文及相关快照，保留必要的无正文删除事件；目录变更日志用于同步，不是可恢复的主题目录。旧客户端不认识目录时的缓存水位限制必须在API说明。撤销/删除设备与应用要一并处理目录、数据和同步隔离，不能留下跨来源孤儿记录。

任务的running/paused/completed/failed/cancelled状态转换及删除规则以 `domain/state.go` 和 `state-version.ts` 为准。完成、失败、取消不能回到运行；清空不能绕过终态。下一轮任务原则上新建主题，不默默重置旧任务。

### 同步与能力

`GET /api/v1/states` 与 `GET /api/v1/topics` 各用自己的cursor/snapshot；游标绑定server和device，不能跨目录或状态复用。固定快照分页，不允许翻页过程中换内容。快照10分钟、每设备最多两个；日志24小时、10000条边界；过期游标410后重新快照。增量必须包含删除语义，客户端按本地事务应用。

通过 `topic_catalog_protocol:1` 宣告目录能力；WebSocket仅向协商相应头的客户端发送状态/目录变化提示。HTTP是恢复数据的依据。新客户端连接旧服务端应隐藏预创建入口并保留已有缓存，不把404解释为请求成功。

## 6. SQLite、迁移与Workers调度

- Go迁移集中在 `store/migrations.go`，当前message schema14；拒绝超出当前版本的数据库。不能在启动时无条件回填目录导致删除对象复活。
- Workers迁移与metadata版本独立于Go，目录表由topicSchema做一次性补齐。不得把两端数据库版本硬绑定。
- 迁移须事务化、可重复安全运行，保持设备身份、消息序号、旧版本水位及幂等记录。增加迁移测试；生产升级前备份。
- 所有外部输入使用SQL参数，不拼接输入到SQL；固定内部表名循环需保持允许列表。
- 保持状态写入、目录修改、版本冲突和删除之间原子性；不能在数据库事务里做HTTP或等待Socket。
- Workers通过Durable Object持久调度和alarm维护，不加setInterval/setTimeout维持业务状态；休眠附件不包含真实凭据正文，重唤醒需重新鉴权。
- Go与Workers均使用有界清理、分页和存储预算，事务失败回滚计数及日志。

## 7. 验证命令与交付

Go（在server目录）：

```bash
gofmt -w cmd internal
go vet ./...
go test ./...
go build ./...
```

Workers（在workers目录）：

```bash
npm ci
npm run check
npm test
npm run build
```

`npm run build` 为Wrangler dry-run，不部署。部署单独使用deploy命令，提交、推送与部署按用户授权区分。CI静态分析与具体版本看 `.github/workflows/`，不跳过失败检查。

新增用例覆盖鉴权隔离、同标识跨来源、幂等重试、并发版本冲突、删除后拒绝上报、手动重建、迁移、过期、分页游标与断线补收；同时验证Go与Workers。HTTP细节修改更新API，样例不得携带真实密钥。二进制、数据库、调试响应和截图放本地忽略目录，不能混入源码提交。
