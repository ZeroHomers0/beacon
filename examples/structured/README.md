# 通用结构化推送示例

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

组件中使用稳定 block id。同一主题可创建多个独立桌面实例，各自选择指标与模板。数值、环形和横条由 App 配置，发送方不指定屏幕布局。
