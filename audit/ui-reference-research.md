# 小笙助手 UI 参考调查

调查日期：2026-10-17
范围：Jetpack Compose / Material 3 官方指导，以及许可证可确认的公开课表项目。本文只提炼交互和视觉原则；没有复制第三方代码、图片、图标或品牌资产，也没有分析官方“超级课程表”APK。

## 官方 Android / Material 3 指导

| 主题 | 一手来源确认的内容 | 对小笙助手的落地建议 |
|---|---|---|
| 视觉层级 | Compose Material 3 的主题由 `ColorScheme`、`Typography`、`Shapes` 等组成，统一配置后会传递到 Material 组件。[Material 3 theming](https://developer.android.com/develop/ui/compose/designsystems/material3) | 先建立全局颜色、字体、圆角和间距规则，再应用到作业、课程表、设置；小笙的拟人化表情作为点缀，不要让每个页面各用一套样式。逾期、已完成、未知时间等语义颜色保持稳定，不能只靠颜色表达状态。 |
| 主导航 | Android 官方建议紧凑窗口中对 3–5 个同等重要、跨页面持续存在的目的地使用 Navigation Bar。[Navigation bar](https://developer.android.com/develop/ui/compose/components/navigation-bar) | 可将“作业 / 课程表 / 我的”作为 3 个一级入口；导入、筛选、设置等放在对应页面的动作区，不再把每个功能都抬成一级导航。若之后一级目的地超过 5 个，应重新归并，而不是继续挤压底栏。 |
| 内容卡片 | Material `Card` 适合承载一块语义完整的内容；官方将卡片描述为较小的内容容器，整个页面骨架更适合由普通布局承载。[Card](https://developer.android.com/develop/ui/compose/components/card) | 每个作业/课程作为一个清楚的内容单元；课程表网格保持紧凑，不要在卡片内再套卡片。课程名、时间、地点优先，状态和操作次之；把装饰背景留给页面而不是每个嵌套区域。 |
| 手机与大屏 | Window size classes 按应用当前可用宽、高分类为 compact、medium、expanded、large、extra large，官方建议据此设计和测试自适应布局。[Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/window-size-classes) | 手机窄屏优先日视图/可切换日期，周视图应能横向浏览且课程卡片不挤成不可读的细条；宽屏再展示完整周网格。断点依据可用窗口而不是设备型号名称。 |
| 可访问性 | Compose 可用 semantics 表达控件角色、状态、描述和导航顺序，也提供大字缩放、检查和测试能力。[Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility) | 图标按钮应提供可读描述；选择周次、展开课程、完成作业等状态必须对 TalkBack 可感知；长按批量模式也要有明确的进入、退出和选择状态。 |
| 点击和对比度 | Android 官方建议触控目标至少 48 × 48 dp；小于 18 sp 的普通文字（或小于 14 sp 的粗体文字）对比度至少 4.5:1，其他文字至少 3:1。[Accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps) | 周切换、导入、完成、弹窗关闭等常用动作保留足够触控面积；课程卡片里的浅色辅助文字需实测对比度。不能因“柔和/可爱”主题而把文字做浅、做小。 |

## GitHub 课表参考

### Potato-DiGua/Timetable（LightTimetable）

- [项目 README](https://github.com/Potato-DiGua/Timetable/blob/master/README.md) 将其描述为简洁的课程表应用，列出 Excel 导入、教务系统导入、课程提醒、背景和卡片透明度设置等能力，并明确披露其导入格式与冲突课程方面的限制。
- 源码能确认实际层级：[主布局 XML](https://github.com/Potato-DiGua/Timetable/blob/master/app/src/main/res/layout/activity_main.xml) 将当前周次放在星期表头之前，节次编号在左，课程内容落在七天的网格列中；README 说明一天最多 12 节。可借鉴“周次 / 星期 / 节次 / 课程块”的稳定阅读顺序和紧凑网格密度。
- [主菜单 XML](https://github.com/Potato-DiGua/Timetable/blob/master/app/src/main/res/menu/menu_main.xml) 把教务导入、Excel 导入、修改当前周数、添加课程、分享、设置时间、设置和检查更新放在同一动作菜单。适合借鉴“主视图专注查课、低频管理动作收在菜单里”的分层原则，但不代表必须采用完全相同的菜单。
- [颜色资源](https://github.com/Potato-DiGua/Timetable/blob/master/app/src/main/res/values/colors.xml) 使用蓝色主色，并为课程块定义了多种带透明度的颜色。可借鉴用颜色帮助快速区分课程块；不应照抄这些具体色值，且文字对比度仍须独立测量。
- [仓库 LICENSE](https://github.com/Potato-DiGua/Timetable/blob/master/LICENSE) 是 MIT（版权标注为 Potato-DiGua，2020）。MIT 允许使用、修改和分发，但若复制软件或其实质部分，必须保留版权与许可声明。
- 这个项目可作为“课程表优先、导入与提醒围绕课表服务”的产品流程参考；它是独立开源项目，README 称其为仿课程表产品，不是官方“超级课程表”。
- 无障碍方面不应照搬：核对的主页面 `ImageButton` 没有 `contentDescription`，主活动还对 `ClickableViewAccessibility` 使用了 `SuppressLint`。因此该项目适合作为网格层级/密度参考，不是 TalkBack 或无障碍质量范例。
- README 的截图链接指向仓库外的图床。仓库 MIT 文件不能单独证明该外链图片的权利状态；本报告不复用图片，也不把它当作已核验的配色、字体、卡片尺寸或无障碍实现证据。
- 当前小笙助手仅借鉴公开功能思路，没有从该仓库复制代码或资源。若未来确实复制代码或实质性片段，再按 MIT 要求在随附材料中保留版权和许可文本，并记录具体文件来源；外部图片、图标和表情包仍需单独确认授权。

## 综合建议

先把主流程做顺：作业列表负责筛选、分组和批量动作；课程表负责周次、日/周切换与课程导入；“我的”集中账号、提醒和权限。课程/作业主体保持紧凑、可扫读的单层内容块，层级由标题、间距、字号和轻量状态色建立。小笙的人格适合放在空状态、导入成功/失败、提醒文案和桌宠入口中，用少量表情与短动画传递情绪；不应常驻遮挡课表或每个列表行都重复出现。

## 核验限制

GitHub 项目使用其公开 README 与 LICENSE 作为一手来源；本次未逐页验证它的所有代码、运行行为或第三方图片权利。Material 规则按官方文档转述，具体页面仍需在模拟器和真机上检查 TalkBack、字体放大、窄屏和横屏表现。本文不是法律意见，也没有确认任何第三方截图或素材可随小笙助手分发。
