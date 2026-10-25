# 外部 Webhook 适配器

Python标准库示例，将GitHub或Grafana事件转换为Beacon普通消息。适配器独立于Beacon部署，不内置到Go/Workers，不创建数据主题、不读取设备接收或管理凭据。原生接口见[API文档](../../API.md)。

## 运行

Python 3.10及以上；无需pip安装依赖。以下使用Linux shell，地址与文件路径为占位值：

```bash
export BEACON_URL=https://push.example.com
export BEACON_PUSH_KEY_FILE=/run/secrets/beacon-push-key
export GITHUB_WEBHOOK_SECRET_FILE=/run/secrets/github-webhook-secret
export GRAFANA_WEBHOOK_TOKEN_FILE=/run/secrets/grafana-webhook-token
python3 examples/webhooks/beacon_webhook.py
```

每个`*_FILE`也可改用同名无`_FILE`环境变量；文件优先，只移除文件尾换行。Beacon使用设备推送密钥或应用令牌（64位十六进制），不是管理令牌；服务地址必须HTTPS，不允许URL携带凭据、查询参数或fragment。

至少配置一个来源密钥，否则启动失败；未配置的来源返回404。默认监听`127.0.0.1:8090`，可用`WEBHOOK_HOST/WEBHOOK_PORT`修改。通过自己的HTTPS反向代理转发以下两个路径，保留认证/签名头，并限制请求大小和访问频率。该示例为低流量串行HTTP服务，不提供生产级入口防护或无限工作队列。

## GitHub

- Webhook地址：`https://adapter.example.com/github`，Content type选`application/json`。
- Secret与`GITHUB_WEBHOOK_SECRET`一致；按原始请求体验证`X-Hub-Signature-256`。
- 支持push，以及workflow_run的completed事件；其他事件202 ignored。
- 提交摘要最多5条，工作流结果包含安全的HTTPS详情链接。

## Grafana

- Contact point使用Webhook，地址：`https://adapter.example.com/grafana`。
- 配置`Authorization: Bearer <GRAFANA_WEBHOOK_TOKEN>`；与适配器密钥一致。
- 接受`status:firing/resolved`，展示标题、状态、消息和前5项告警摘要。
- 不支持把Beacon令牌当Webhook入口密钥。

## 可靠性与限制

- 输入最大256KiB，仅JSON对象，拒绝重复键、非有限数值和不完整请求；不接受chunked上传。
- 验签/认证失败不推送；仅映射固定字段，不执行模板或脚本，不自动查询业务服务。
- 根据认证后的规范化payload生成稳定幂等键，不信任未签名的delivery头作为唯一身份。相同内容重放/重试复用同一键，借助Beacon七天幂等窗口避免重复消息；这不是无限期防重放保证，入口应使用来源密钥和访问控制。
- 最多3次发送，每次10秒超时；429/5xx及网络中断有界退避，永久参数/权限失败不重试。单次退避最多60秒；服务器要求更长等待时返回502，由上游稍后重试，不能提前重发。无常驻轮询。
- 202 accepted只表示Beacon接受并排队，不表示手机已收到；502 delivery_failed应由上游按重试策略再次投递。适配器无持久化转发队列，不能在已接受后悄悄丢事件。
- 不跟随Beacon重定向，不输出凭据、真实payload或异常URL；响应只含固定状态码。生产可由服务管理器收集不含敏感内容的进程状态。
- 所有输出是普通事件。需要持续更新的数据主题应另写明确的采集适配器，先预创建主题并定义revision分配，不复用事件ID冒充revision。

## 本地验证

```bash
python3 -m unittest discover -s examples/webhooks -p 'test_*.py'
```

测试模拟HTTP响应，不调用生产API、不发送手机通知，覆盖验签、重复事件幂等键、非法JSON、恢复事件、URL限制及有界重试。
