# Beacon 二级页面 KSU 风格改动清单

日期：2026-10-24。依据两个项目 main 分支的页面与共用组件源码审阅；未安装或运行参考应用。本文是设计与验收清单，不表示以下项目已经实现或通过验收。

## 参考依据

- [KernelSU 状态与信息卡](https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/HomeMiuix.kt)：状态底色、右下角 CheckCircleOutline、分组信息行。
- [KernelSU 设置](https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/ui/screen/settings/SettingsMiuix.kt)：同类设置组合在同一 Card，开关、选择、跳转采用对应偏好行。
- [KernelSU 应用配置](https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/ui/screen/appprofile/AppProfileMiuix.kt)：对象摘要与配置项分组。
- [HyperOS_FCM_Live 状态页](https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HyperFCMLive/src/main/java/io/github/howard20181/hyperos/fcmlive/ui/ModuleStatusScreen.kt)：状态摘要、设备信息、工具入口分开。
- [HyperOS_FCM_Live 共用组件](https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HyperFCMLive/src/main/java/io/github/howard20181/hyperos/fcmlive/ui/ScreenParts.kt)：SettingsGroup、GroupRow、SettingsSwitchCard、SettingsMenuCard、NavCard、AppDialog。
- [HyperOS_FCM_Live 应用列表](https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HyperFCMLive/src/main/java/io/github/howard20181/hyperos/fcmlive/ui/MainScreen.kt)：名称、标识、状态与选择操作分工。
- [HyperOS_FCM_Live 实验设置](https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HyperFCMLive/src/main/java/io/github/howard20181/hyperos/fcmlive/ui/ExperimentScreen.kt)：按功能分组的开关与选项。
- [HyperOS_FCM_Live 关于与设置](https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HyperFCMLive/src/main/java/io/github/howard20181/hyperos/fcmlive/ui/AboutScreen.kt)、[许可页](https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HyperFCMLive/src/main/java/io/github/howard20181/hyperos/fcmlive/ui/LicensesScreen.kt)：信息、菜单、长正文分开。

## 设计边界

- 保留 MIUIX / Material 3 切换、现有框架与图标；两套主题保留各自原生控件及反馈。
- 两套主题共享信息顺序、组件尺寸规则、按钮语义和数据状态。
- 文字按钮只有一种视觉样式，不恢复主次分级。危险操作仅红字区分并保留确认。
- 不引入玻璃模糊、彩色渐变、大幅 Logo 动画、卡片倾斜、额外导航条或新的装饰图标。
- 不借鉴参考项目中的自动伸缩布局、依赖异步数据出现的可变信息行、无实际行为的可点击信息行。

## P0：共用规则与稳定性

- [x] 统一页面横边距、卡片圆角、卡片内边距和组间距，使用现有 BeaconUiMetrics，避免叠加嵌套卡片内边距。
- [x] 状态卡：两行真实摘要 + 右下角叠放的正常状态标记；图标不占文字布局宽度、不遮挡文字。
- [x] 信息行：标题在上、值在下，两者均单行；显示前测量缩字，达到可读下限后省略，完整值可查看或复制。
- [x] 普通设置行：现有图标 + 标题/简短状态 + 唯一尾部控件；整行与开关不重复触发操作。
- [x] 菜单行与按钮分工：导航、选择、工具入口使用设置行；提交或执行动作使用统一按钮；同一动作只有一个入口。
- [x] 弹窗：统一标题、左对齐正文、内容滚动、底部等宽按钮；单按钮满宽。长正文允许换行，不挤压底部操作。
- [x] 异步刷新保留上次成功快照；字段缺数据以明确占位值显示，不插入空白行。
- [x] 正常、加载、失败更新同一个字段；完整错误进入统一详情弹窗，不在卡片内临时新增提示块。
- [x] 单行文字缩字后保持其原有逻辑行高；系统字体变化时整体适配，不使用锁死的物理像素高度。
- [x] 每页一个对应的刷新入口；只有用户点击的入口显示动画；后台操作不得联动旋转其他按钮。
- [x] 当天时间 HH:mm:ss；其他日期 yyyy-MM-dd HH:mm:ss，按手机本地日期判断。

## P1：逐页改动

| 页面 | 改动内容 | 主要参考 |
| --- | --- | --- |
| 服务器连接 | 统一状态摘要；地址只显示一处；设备名称、上次检查、结果固定顺序；编辑动作固定位置；错误详情不撑卡片 | KSU 首页、FCM 状态页 |
| 接收模式 | 状态摘要 + HTTPS/即时连接/最近同步信息；模式选择、即时配置、省电配置独立分组；一个同步入口 | KSU 设置、FCM 实验设置 |
| 服务器概况 | 状态摘要 + 版本/兼容性/检查结果；统计独立三块；退出与刷新位置固定 | KSU 首页、FCM 状态页 |
| 存储与清理 | 占用与数量分块；自动清理快照优先展示；清理规则用统一设置行；预览统计、执行动作、危险操作各自成组 | KSU 信息卡与设置分组 |
| 后台权限 | 三项权限共用行布局；标题、状态、跳转入口对齐；保持已移除的说明入口不再出现 | FCM 共用设置行 |
| 通知与免打扰 | 授权、待通知、频道分别成组；开关/选择/时间段样式一致；状态变化不增减操作行 | KSU 设置、FCM 设置菜单 |
| 设备管理 | 列表固定展示名称、标识、绑定状态；次要动作收进统一更多菜单；详情弹窗呈现完整标识与时间 | KSU 列表与应用配置、FCM 应用列表 |
| 应用与密钥 | 名称、所属设备、令牌状态固定布局；复制/撤销/删除进菜单；新建令牌仍只在受控弹窗展示，不泄露真实令牌 | KSU 应用配置、FCM 应用列表 |
| 备份恢复 | 设置备份、消息备份各成组，导入/导出使用菜单行；结果通过统一反馈与详情弹窗展示，不撑开操作卡片 | KSU 设置行、FCM AppDialog |
| 开发者工具 | 保留复制推送密钥菜单行；推送示例弹窗直接显示四个复制选项，不恢复分类 | FCM 菜单行、统一弹窗 |
| 测试发送 | 输入字段统一间距；提交按钮固定位置；发送状态替换原状态，长报告进结果弹窗 | KSU 操作页、FCM 弹窗 |
| 使用向导 | 步骤、短状态、入口固定；测试结果不插入长正文；不因步骤完成自动扩张卡片 | FCM 状态页入口分组 |
| 关于与更新 | Logo、名称、版本保持紧凑；项目/文档等链接组成菜单卡；更新与许可证长内容使用滚动弹窗 | 两个项目的关于及许可页面 |

## P2：回归验收

- [ ] MIUIX / Material 3 × 浅色 / 深色 × 普通 / 2 倍字体。
- [ ] 窄屏、长域名、长设备名、长版本号、最大位数统计值。
- [ ] 首次进入、重复进入、刷新、断线、失败、恢复和连续点击。
- [ ] 同一窗口尺寸与字体设置下，异步数据替换前后卡片及按钮边界保持不变。
- [ ] 状态摘要与信息行没有换行、空白占位行或文字和右下角标记重叠。
- [ ] 完整长值与错误可以查看；省略仅影响摘要，不丢失数据。
- [ ] 单次点击只发起一次动作；页面上不出现多个联动刷新的图标。
- [ ] 键盘打开后，弹窗操作仍可见；返回、外部点击、禁用状态行为一致。

## 实施与验收状态

本轮已落实 P0 共用组件与 P1 逐页调整，详见 [实施与验收记录](settings-ui-implementation.md)。已解决 Windows JVM 临时套接字路径导致的 Gradle 启动问题；应用与设备测试 APK 构建成功，329 项单元测试、最终 56 项设备回归通过（包含前期 52 项与后续补充用例）。Android Lint 为 0 错误，仍有 125 条警告与 1 条提示。生成 120 张模拟器截图，覆盖两套主题、深浅色、普通/2 倍字体、长页面下部及管理组件。

上述 P2 条目保留为后续真实手机验收清单；模拟器覆盖及边界详见实施记录。新增的许可证页面不在当前功能范围，本轮统一现有关于与更新弹窗。改动在 main 完成，提交记录见 Git 历史。
