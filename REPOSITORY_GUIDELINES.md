# Beacon 目录与提交规范

目标：仓库只保存可复现开发、构建、验证和使用所需文件。临时目录、个人环境、构建输出和真实运行数据不能进入任何提交；忽略文件不等于允许用 `git add -f` 绕过。

## 1. 根目录职责

| 路径 | 可提交内容 |
| --- | --- |
| `README.md` | 项目介绍、安装部署、快速开始、功能入口、文档链接 |
| `API.md` | 全量HTTP/Bark/实时/主题/小组件发送格式、限制和示例 |
| `FRONTEND_GUIDELINES.md` | 客户端分层、公共UI组件、各类页面/模板的视觉和操作规则 |
| `BACKEND_GUIDELINES.md` | 双后端边界、协议、事务、存储、迁移和验证 |
| `REPOSITORY_GUIDELINES.md` | 本规范；目录白名单与检查入口 |
| `AGENTS.md`、`CLAUDE.md` | AI工作指引，引用上述规范，不能各自复制出一份相冲突的UI/API约定 |
| `android/` | Gradle源码配置、正式资源、测试源码和脱敏测试数据 |
| `server/` | Go源码、测试、依赖锁定、正式Docker/部署配置 |
| `workers/` | TypeScript源码、测试、依赖锁定和Wrangler正式配置 |
| `contract/` | 可复用、脱敏的跨实现协议样例 |
| `examples/` | 用户可运行的示例源码和使用文档，不含脚本运行状态 |
| `mcp/` | MCP服务源码、依赖锁定及说明 |
| `tools/` | 团队可复用检查、迁移辅助、诊断工具；一次性脚本不放这里 |
| `.github/` | 工作流和正式仓库自动化 |
| `.githooks/` | 可审查的提交前检查钩子 |
| `docs/` | 如有必要的正式多文件文档；`docs/design/` 明确仅本地 |
| `.editorconfig/.gitattributes/.gitignore/LICENSE` | 基础配置与许可 |

根目录新增一级目录或文档前，先确认能否归入现有职责；确需新增时同步本表和 `tools/check-repository.mjs` 的允许列表，并在提交说明解释用途。禁止把下载资料、另一份checkout或临时验证工程放进可提交目录。

## 2. 临时目录与产物

| 路径/类型 | 放置与处理 |
| --- | --- |
| `artifacts/` | 本地APK、QA截图、性能报告、验收输出；不提交，可在对话中提供本地链接 |
| `docs/design/` | 本地原型、设计草稿、过程计划；不提交 |
| `docs-repo/` | 独立文档仓库，本源码仓库忽略；在其自己的仓库单独检查变更 |
| `.local/`、`.tmp-*/`、`tmp/`、`temp/`、`scratch/` | 一次性脚本、调查资料、临时响应、实验；不提交 |
| `build/`、`dist/`、`coverage/`、`test-results/` | 编译与测试生成内容，不提交；测试源码和正式fixture仍要提交 |
| `node_modules/`、`.gradle/`、`.kotlin/`、`.wrangler/`、虚拟环境与缓存 | 本机依赖/缓存，不提交，提交对应依赖锁文件 |
| `.env*`、`.dev.vars*`、local.properties、IDE个人设置 | 环境及凭据配置不提交；确需脱敏示例使用明确的example/sample文件并审查 |
| `.db/.sqlite`及WAL、beacon-data、真实device.json及beacon-revisions.json | 服务端与脚本运行数据，不提交 |
| APK/AAB/EXE、签名库、私钥、日志、压缩产物、补丁残留 | 不提交；正式发行通过CI/Release发布 |

不要为避免提交产物而忽略整个 `android/app/src/test/`、`androidTest/` 或正式资源目录。小组件系统预览PNG属于产品资源，应在 `android/app/src/main/res/drawable-nodpi/` 提交；设备测试截图则放artifacts。Gradle wrapper的官方jar是构建依赖，不能按“二进制一律删除”处理。

不得擅自删除本地临时目录中的用户文件。整理仓库时优先忽略；已追踪的不应提交文件应检查后用 `git rm --cached` 移出索引、保留本地，需要清理历史敏感信息时单独说明风险与处理方案，不自动重写Git历史。

## 3. 提交前检查与CI

```bash
node tools/check-repository.mjs
node tools/check-docs.mjs
chmod +x .githooks/pre-commit
git config core.hooksPath .githooks
```

Linux/macOS先执行上述chmod；Windows使用Git Bash设置钩子目录即可。本地配置启用版本化pre-commit钩子，校验**整个暂存区的文件目录和文档引用**，包括被强制添加的忽略文件。钩子需要Node.js 18+；克隆后设置一次，新机器也需设置。GitHub `Repository policy` 工作流对push/PR执行同样检查；要在合并层面强制，仓库管理员还需将该检查设为分支必需状态，代码无法自行代替托管平台保护规则。

检查会拒绝不在允许列表中的根目录、禁止目录和常见产物/凭据文件。它不做完整的秘密内容扫描，也不会发现任意改名后藏在源码中的无关内容；开发者和审阅者仍必须检查diff。禁止使用 `--no-verify` 或关闭CI规避规范。

提交前查看：

```bash
git status --short
git diff --check
git diff --cached --stat
git diff --cached
```

确认新增文件来源明确、无真实密钥/正文/服务器导出，文档链接有效。接口变化同步API，公共UI变化同步前端规范，后端规则变化同步后端规范，目录变化同步本规范和允许列表。只运行与变更相关的验证，文档整理不要求重跑Android或Go全量构建。

提交、推送、部署是不同动作，按用户当前授权执行；不能因生成了APK就提交APK，不能因完成代码就自动部署。
