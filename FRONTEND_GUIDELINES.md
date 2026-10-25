# Beacon 前端开发规范

适用：Android App、系统通知、桌面小组件及配置预览。依据2026-10-25当前代码；“前端”主要指Android客户端，当前没有浏览器管理前端。API定义见 [API.md](API.md)，目录约束见 [目录规范](REPOSITORY_GUIDELINES.md)。新增界面必须沿用现有设计和操作，不允许为单页创建另一套视觉体系。

## 1. 分层与入口

| 位置 | 职责 |
| --- | --- |
| `android/app/src/main/java/io/github/beacon/app/ui/` | App框架、导航、顶栏、共享组件、主题与图标 |
| `feature/messages/` | 普通消息、详情与本机数据列表 |
| `feature/settings/` | 设置、服务器/设备/应用/数据主题管理 |
| `domain/` | 展示模型、规则与纯策略，不直接依赖页面 |
| `data/local/`、`data/remote/` | 本地持久化、安全存储、协议及HTTP/WebSocket |
| `runtime/` | 接收进程、后台调度与运行状态 |
| `widget/` | 组件实例、配置、绘制、通知与定向刷新 |

先查同类页面和公共组件。页面不直接操作网络或保存密钥；通过ViewModel、数据层和已有运行时接口交互。管理会话与设备接收会话分离。`StateCache` 的主题目录、最新数据和各自游标分开保存，不把等待数据伪造成revision=1的状态。

## 2. 双风格和主题

入口为 [BeaconTheme.kt](android/app/src/main/java/io/github/beacon/app/ui/theme/BeaconTheme.kt)。支持 `MATERIAL3/MIUIX` 与 `SYSTEM/LIGHT/DARK`。使用 `Beacon*` 包装组件桥接风格；业务页不直接混搭独立Material或MIUIX弹窗、按钮、输入框。

Material支持Android 12及以上动态色，低版本使用固定后备色。因此普通页面必须取 `MaterialTheme.colorScheme`，禁止把后备色当成全局固定主题。MIUIX也经由同一入口桥接颜色和文字样式。

| 语义 | 取值/规则 |
| --- | --- |
| 主要文字 | `onSurface`；次要说明 `onSurfaceVariant` |
| 页面/卡片 | `background/surfaceContainerLow` 等公共组件选定的语义色 |
| 错误/危险操作 | `error`，不能用成功绿表示仅已排队或正在连接 |
| 通用成功色 | `beaconSuccessColor()`：浅色#16754D，深色#6DDBA8 |
| 选中标签 | `BeaconChoiceSelected` #91CEFF，未选中#DDECFF，文字#14263D |
| 标签配色 | `BeaconTagPalette` 按稳定标签策略映射，不随列表重排变色 |

连接、运行、服务器概况等健康卡片复用 [SettingsOverviewCard](android/app/src/main/java/io/github/beacon/app/ui/components/SettingsOverviewCard.kt)：浅色背景#DFFAE4、文字#14261A；深色背景#193927、文字#E9EBEA；圆圈对钩#35D267。成功标记为不参与测量的右下角覆盖层，不能挤压文字或让状态变化改变卡片高度。

## 3. 几何与排版基准

唯一数值来源为 [BeaconUiMetrics.kt](android/app/src/main/java/io/github/beacon/app/ui/components/BeaconUiMetrics.kt)，调整全局尺寸必须修改公共常量并验证同类页面，不允许局部“差不多”的硬编码。

| 类型 | 当前值 |
| --- | --- |
| 页面左右/顶部/底部 | `PageInset`16dp / `PageTopInset`12dp / `PageBottomInset`24dp |
| 卡片内边距 | `CardInset`16dp |
| 普通段落/设置组/组标题间距 | `SectionGap`12dp / `SettingsGroupGap`20dp / `GroupTitleGap`8dp |
| 普通卡片圆角/按钮圆角 | `CardRadius`16dp / `ActionRadius`12dp |
| 设置项最小高度/垂直内距 | `FieldMinHeight`61.6dp / `FieldVerticalInset`13.2dp |
| 通用控制最小高度/操作最小宽度 | 48dp / 64dp |
| 搜索框最小高度/垂直内距 | 36dp / 6dp |
| 搜索前导内距/图标间距/图标 | 14dp / 10dp / 18dp |
| 列表卡片垂直内距/卡片间距 | `MessageCardVerticalInset`10dp / `MessageCardGap`8dp |
| 卡片三点操作区/图标 | 32dp / 20dp；不能缩小点击区来对齐 |
| 小/行内/标准图标 | 16dp / 18dp / 24dp |
| 状态图标方块/未读点 | 48dp / 6dp |
| 徽标圆角/垂直内距 | 6dp / 3dp |
| 行内间距 | 使用Gap2、4、6、8、10、12、16，优先选有语义的常量 |

全局文字沿用Material typography并由主题桥接：行高通常1.4em，headlineLarge为28sp、headlineSmall为22sp、bodySmall为13sp。密集列表单独使用 [BeaconListText](android/app/src/main/java/io/github/beacon/app/ui/components/BeaconListText.kt)：

| 列表文字 | 字号/行高 | 行为 |
| --- | --- | --- |
| 标题 | 15sp / 20sp | 标题优先，全文可通过详情查看 |
| 副标题 | 13sp / 18sp | alpha=.75，不能与标题同等抢眼 |
| 正文预览 | 13sp / 18sp | 最多2行，次级文字色 |
| 搜索文字 | 14sp / 20sp | 配合36dp搜索框 |

长标题、设备ID、URL等使用 `BeaconFittedText`、详情入口或规定的省略行为，不能把整张卡片撑宽。提示指引与错误原因需要完整可读时使用换行，不默认套用单行省略。支持系统大字体，不通过全局禁用字体缩放维持布局。

## 4. 页面类型与标准布局

### 4.1 一级与二级设置页

使用 `BeaconScaffold/BeaconTopBar`、现有bar behavior及 `BeaconSettingsList`。统一处理系统Insets与顶栏padding，避免在内容中重复叠加标题和额外顶部空白。二级页面标题到首行距离、左右边距须与已有设置页一致；关于页不再额外放一个大标题。

设置分组使用 `SettingsGroup/SettingsLabel/SettingsRow/SettingsHint`。导航项采用现有尾部指示，说明放次级文字；无描述的“检查更新/关于/首次使用向导”放紧凑的独立组。相同字段使用 `SettingsFieldGroup` 等现有组件，不因操作结果临时插入大块说明挤压原行。

### 4.2 搜索、筛选与管理列表

以消息、设备、应用管理页面为基准，主题列表复用 `AdminListCard`：

1. 搜索框固定在内容上方；下方放 `BeaconSummaryPill` 统计，再放当前筛选标签。
2. 筛选在顶栏右侧图标中；需要创建能力时再显示新增按钮。
3. 设备、应用、主题、有效/撤销、本页统计使用各自语义图标，不能全部复用消息图标。
4. 卡片操作收在右侧三点菜单，通过 `FirstLineSlot` 与标题第一行垂直居中对齐；不可按整张卡片高度居中。
5. 使用 `BeaconPullToRefresh`，设备/应用/主题列表不再增加重复的右上角刷新按钮。
6. 服务器概况负责统一刷新和主题预载；主题列表重进时复用已有查询结果，筛选变化/下拉才重新请求。不要将其扩写为所有页面都禁止首次加载。
7. 空列表、首次加载失败、保留旧数据的刷新失败要区分，复用 `PageState/SettingsRetryFeedback`。

服务器管理的登出操作位于顶栏右上角，使用Logout图标并确认。主题必须预创建；删除后不显示“已删除”卡片或恢复入口。旧states接口的数据清空与目录删除不得混淆。

### 4.3 消息和数据详情

普通消息与数据主题分开：消息参与历史、未读与接收确认；主题展示最新状态。详情复用 `StructuredBlocksPanel/MessageActionsPanel`，不能按服务名称写专属页面。Markdown图片默认不下载，链接由用户点击确认后打开；复制、分享和搜索以原文为准。

消息详情使用独立顶部卡片承载标题、副标题、标签和原文/排版切换；正文及结构化内容放下方独立卡片。“原始信息”标题行右侧提供箭头图标按钮：折叠向下、展开向上，48dp点击区，保留“展开原始信息/收起原始信息”无障碍说明，默认折叠且不显示空卡片，不再单独放“查看消息信息”按钮。

### 4.4 表单、按钮与保存

| 类型 | 组件/操作要求 |
| --- | --- |
| 文本、密钥、数字输入 | `BeaconTextField`；限制与后端一致；密钥遮罩、不回显、不进saved state或截图 |
| 下拉选项 | `BeaconDropdownPreference`；禁用时给出原因，保持所选项和布局稳定 |
| 开关/勾选/筛选 | 复用Beacon组件；点击子操作不能误触整行选择 |
| 普通/次级/危险按钮 | `BeaconButton/BeaconTextButton/BeaconOutlinedButton/BeaconDangerButton` |
| 顶栏保存 | `BeaconSaveAction`；有效且有未保存改动才可用，onSurface深色；否则alpha=.38 |
| 保存结果 | 成功更新基线，留在当前页并灰显；失败保留草稿可重试；不能用绿色勾替换保存图标 |

修改后又恢复原值，应重新视为无改动。保存仅提交当前草稿一次，异步处理中阻止重复执行。首次桌面配置保存后设置RESULT_OK，手动返回完成宿主添加，不能因保存而强制退出编辑。

### 4.5 弹窗与反馈

统一用 `BeaconAlertDialog/BeaconConfirmDialog`；危险操作必须说明影响，确认文字用“删除/清空/撤销”等具体动词。失败留在原弹窗说明，不关闭后让用户猜结果。表单内容独立滚动，按钮保持可触达；MIUIX弹窗最大屏高85%，Material正文最大55%，由公共组件管理。

所有双按钮弹窗统一由 `BeaconAlertDialog` 在底部同一行等宽展示：取消在左、确认/创建在右，两者等高、间距12dp，最小点击高度48dp；不能因文字、状态或字体设置改成上下两行。正文独立滚动，不能挤压操作区。

弹窗操作文字统一13sp，仍尊重系统字体缩放。按钮文案最多四个汉字，优先使用“取消/创建/保存/删除/确认/下载更新/选择文件”等明确短动词；长说明放标题或正文。创建按钮统一“创建”，不重复“创建绑定码/创建发送应用”等长标题；空间不足先缩短文案到“创建”等两字，不用省略号截断、过度缩字或缩小点击区。极端大字体允许按钮内部换行并同步等高，两个按钮本身仍同行均分。危险操作缩短后必须保留行为含义，完整影响写在正文。

`BeaconActionPair/SettingsActions` 的自适应排列仅用于页面内操作组，不能替代弹窗固定双列操作。使用统一Snackbar报告短结果；不要因加载、失败或成功突然增加占位行造成内容跳动。错误槽可预留固定高度，较长解释通过详情或换行展示。

## 5. 小组件的完整样式约束

### 尺寸、预览和容器

[WidgetInstances](android/app/src/main/java/io/github/beacon/app/widget/WidgetInstances.kt) 定义规格，XML声明目标格子，渲染器使用桌面返回的真实dp尺寸，不能把后备预览尺寸当成真实屏幕尺寸。

| 规格 | 后备预览dp | XML最小dp | 预览资源 |
| --- | --- | --- | --- |
| 2×2 | 180×180 | 110×110 | `state_widget_preview`，大单环 |
| 3×2 | 270×180 | 180×110 | `state_widget_medium_preview`，中宽进度卡 |
| 4×2 | 360×180 | 250×110 | `state_widget_wide_preview`，宽进度卡 |
| 4×4 | 360×360 | 250×250 | `state_widget_large_preview`，四环 |

当前不提供4×3。四个provider分别绑定自己的previewImage与previewLayout；不能复用宽卡图片冒充大方卡。正式预览PNG放资源目录，用 `WidgetPickerPreviewTest` 调用真实 `WidgetRenderer` 生成；QA截图放忽略的artifacts。缩放后应保持对应规格的视觉，实际网格仍以桌面为准。

背景圆角固定27dp（受极小尺寸几何上限保护），不能按卡片宽高百分比随大卡放大。画布按物理像素密度绘制，保留抗锯齿与位图预算，禁止低分辨率整图放大。小组件与设置预览共用绘制器，不能分开做两套排版。

### 十类模板

| 模板ID | 样式与限制 |
| --- | --- |
| `numbers` | 纯数字；通常1–4项，大卡最多8项；值和说明分区 |
| `horizontal_bars` | 横向进度；通常最多4项，大卡最多6项；无确定范围可用不定进度 |
| `vertical_bars` | 纵向柱图；2–6项，必须相同单位与范围；窄卡空间不足时禁用 |
| `single_ring` | 单环，仅1项，明确范围；数值与短标题在内径安全区 |
| `nested_rings` | 2–3个嵌套环；右侧图例只在WIDE/LARGE，窄卡只画环及环内标题 |
| `ring_grid` | 2–4个独立小环；每环文字受内径、粗细及格子高度限制 |
| `segmented_ring` | 一组segments，多个颜色表示组成；不能直接混入普通数值块 |
| `text_list` | 一组list；2格宽单列、4格宽双列；起始排名、条数和单双行由有界配置决定 |
| `text` | 整段body；换行、字号与颜色；超长正文按模板既有规则处理 |
| `sparkline` | 一组sparkline；需要最小高度；通知栏可降级为摘要 |

最终兼容判断必须调用 `WidgetConfigRules.error/layoutError`，不兼容选项置灰并显示原因。条目数量符合还不够，真实宽高也必须通过布局判断。模板顺序可优先显示兼容项，但不得改变用户已有手动设置。

### 字号、颜色、占位与交互

- 数字比例仅85/100/115%，说明文字85/100/110%，图形粗细80/100/115%；三个嵌套环的实际粗细最多1.1倍。text/list字号提供12/14/16/18/20/24档，最终仍由模板约束；以 `WidgetTuning` 为准。
- 文字和图形要保留卡片、单元格、环内边距；字号增大时优先重新测量或禁用组合，不能交叠、溢出、贴边或让环把文字全部挤掉。
- 颜色入口使用“自选颜色/按状态变色/分段提示颜色/占比圆环”等现有直白文案。颜色前显示实际色圆点，支持统一与单项覆盖；优先级以 `WidgetColors/WidgetColorSettings` 为准。
- 未绑定指引使用 `WidgetPlaceholder` 的完整换行布局，不调用单行 `fit/cap` 添加省略号；小卡先换行再在安全区域适配字号。不得编造0%、成功状态或假榜单。
- 绑定空主题显示“等待数据”，模板为auto；有数据后通过 `resolved()` 选择兼容模板和默认内容，不能覆盖用户手动选择。
- 内容排序在第一项禁用上移、末项禁用下移；按钮区域不能冒泡触发行勾选。选择内容与“按发送应用筛选”分离，筛选本身不改变绑定。
- 桌面单击不进入编辑；使用宿主长按编辑或App已有组件管理入口。App不提供添加/复制组件入口，管理页面不显示“本进程”调试统计。

## 6. 通知、功耗与生命周期

系统通知只展示摘要、一条主进度和受平台约束的操作；完整blocks在详情和小组件。状态更新保持静默，不反复响铃；任务完成通知作为独立事件发送。等待数据或已删目录不伪造状态通知。

保留定向失效、显示指纹缓存、8MiB位图缓存与5/15/60秒刷新节流；不要每条数据重绘所有实例。系统宿主刷新同样按显示签名去重，保留每小时低频恢复；数据变化广播按150ms合并，通知只加载变化主题，每批只读一次活动通知。息屏时保留最新数据而不绘制桌面位图，亮屏补刷；进程退出后的恢复由宿主回调、启动和数据广播兜底。普通状态通知按实际标题、摘要、主进度、操作和状态去重，亮屏最多每5秒、息屏最多每60秒更新，首次、完成/失败/取消与删除/过期立即处理，尾部更新不得丢失。

实时心跳与主题变化回调分离；首次订阅及重连立即补收，变化信号250ms合并且持续变化时最多每5秒触发一次补收，健康连接兜底间隔依次为5/10/15分钟并封顶。心跳顺带检查到期兜底，独立Handler仅作为非唤醒安全网，暂停/销毁取消。不得把每次心跳变成HTTP全量检查。

可见页面使用生命周期观察和一次性到期计时，不新增秒级轮询。网络、数据库工作离开主线程，页面退出停止观察。修改渲染结果时更新绘制缓存版本，避免沿用旧位图。

## 7. 验证和交付

在 `android/` 使用Java21与仓库Gradle wrapper：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
```

按改动选择有意义的设备测试，不为纯文案逐字写实现镜像测试。重点参考 `AdminTopicsTest`、`SettingsToolbarSaveTest`、`WidgetSaveBehaviorTest`、`TopicCatalogTest`、`WidgetPickerPreviewTest`、`StructuredStateTest`。验证两种UI风格、明暗主题、小屏/大字体、长文本、空/错误/等待/离线状态，以及真实宿主尺寸。说明模拟器和小米真机验证范围，不能用构建通过代替实机确认。截图、APK与性能报告放artifacts，不提交。

### 功耗对照验收

使用 [measure-widget-power.ps1](tools/measure-widget-power.ps1) 在同一真机、同一网络/亮度/接收设置下，分别采集优化前后Start/End；产物只进入忽略的`artifacts/widget-power/`。至少覆盖8小时息屏无消息、持续下载、四个组件、弱网断连四组，优先使用无线ADB以避免USB充电影响。脚本不重置全机电池统计，比较同一窗口的增量；不要把模拟器或短时电量百分比作为节电结论。

同时核对系统batterystats中的网络活动、唤醒锁、CPU、任务执行和连接重试；结合现有小组件刷新/绘制/复用计数验证：未变内容不重绘，息屏数据不绘制，亮屏恢复最新值，终态不受通知节流阻挡，断网无请求风暴。网络字节数不能单独证明耗电减少。通知/组件按最新状态展示，普通消息历史及接收确认不丢弃。
