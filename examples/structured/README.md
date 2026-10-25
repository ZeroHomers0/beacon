# 通用结构化推送示例

完整请求格式见根目录 [API.md](../../API.md#实时数据主题与小组件发送-api)。运行前先在App「服务器管理 → 数据主题」创建对应标识，例如 `quota:account-a`、`download:task-123`、`disk:nas`、`metrics:server`；发送应用必须与脚本凭据匹配，未关联应用时用设备推送密钥。脚本不会自动创建主题，未登记返回404。

Python 3，无第三方依赖。设置 `BEACON_URL`、`BEACON_PUSH_KEY` 后运行：

```powershell
python examples/structured/send_state.py quota account-a --value 24
python examples/structured/send_state.py download task-123 --unknown-total
python examples/structured/send_state.py download task-123 --value 40
python examples/structured/send_state.py download task-123 --value 100 --status completed
python examples/structured/send_state.py disk nas
python examples/structured/send_state.py metrics server
```

示例值用于演示，不内置任何服务查询或凭据采集。一个 task key 对应一次任务，终态后新下载用新 key。`--journal` 指定本地版本日志，重启仍复用；同一主题仅一个写入者。日志不保存推送密钥；不要删除尚有不确定请求的日志。

脚本先检查能力和主题水位，持久保存待发送快照，重试沿用 revision；429/暂时错误退避。下载终态另外发送有稳定幂等键的事件以保留历史并提醒。生产调度器有变化时建议每 1–2 秒最多上报一次，多个主题需共享限额并合并最新值。

组件中使用稳定 block id。同一主题可创建多个独立桌面实例，各自选择内容与模板。数值、环形和横条由 App 配置，发送方不指定屏幕布局。

本地版本日志 `beacon-revisions.json` 属于运行数据，已被仓库忽略，不要提交。

## 高频下载上报与功耗

`send_state.py download` 默认仅在距上次成功上报至少5秒、进度变化至少1个百分点时发送；连续300秒无上报仍发送保鲜，避免600秒新鲜期内提前变旧。首次、暂停/继续、是否已知总量变化，以及完成/失败/取消始终发送。普通额度、磁盘等示例不应用下载节流。

可使用`--min-interval 10 --min-change 2 --keepalive 300`调整；参数约束为`0 <= min-interval <= keepalive <= 300`、`0 <= min-change <= 100`。被合并的采样不会发送HTTP，也不会占用revision；尚未确认的请求优先恢复。复用同一个journal，一个主题仅允许一个写入者；客户端已展示终态后，新任务使用新的topic key。

外部实际采集程序也应在网络请求前按同一原则合并，仅将最新进度交给Beacon，完成/失败立即提交。客户端显示节流不能消除脚本已产生的网络成本。离线回归：`python -m unittest discover -s examples/structured`。
