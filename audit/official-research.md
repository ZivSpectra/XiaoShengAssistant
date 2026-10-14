# 官方资料与学习通页面证据审计

更新时间：2026-10-06（Asia/Shanghai）

本文只记录可复核的资料和本项目已经观察到的页面证据。没有记录任何账号、密码、Cookie 或完整会话值。学习通接口是登录后页面实际使用的端点，不代表学习通向第三方开发者提供了稳定公共 API。

## 1. Android 通知与后台提醒

### 1.1 Android 13+ 通知权限

来源：Android Developers，**Notification runtime permission**，<https://developer.android.com/develop/ui/views/notifications/notification-permission?hl=en>（官方文档，访问 2026-10-06）。

- 在 Android 13（API 33）及更高版本，新安装应用的通知默认关闭；应用必须声明并在运行时请求 `android.permission.POST_NOTIFICATIONS`，用户授予后才能发送通知。
- 目标 SDK 33 或更高时，应用可以自行选择合适时机展示权限对话框。应在解释提醒用途之后请求，而不是在用户没有上下文时突然请求。
- 用户拒绝后，应用不能绕过系统重新开启通知；应提供应用通知设置入口，让用户自行恢复。

对本项目的直接要求：首次启动/首次设置提醒时检查 `POST_NOTIFICATIONS`，未授权时清楚说明原因并请求；设置页必须保留“打开系统通知设置”入口。仅调用 `notify()` 不能证明通知已经允许。

### 1.2 通知频道的声音、重要性和用户控制权

来源：Android Developers，**Create and manage notification channels**，<https://developer.android.com/develop/ui/views/notifications/channels?hl=en>（官方文档，访问 2026-10-06）；API 参考 **NotificationChannel**，<https://developer.android.com/reference/android/app/NotificationChannel>。

- Android 8.0（API 26）及以上，所有通知必须归属一个通知频道。
- `IMPORTANCE_HIGH` 对应会发出声音并显示 heads-up 的高干扰通知；`IMPORTANCE_DEFAULT` 会发出声音但不一定 heads-up；低重要性频道不发声。
- 频道创建后，应用不能再次覆盖重要性或其他通知行为；用户可以在系统设置中修改频道的声音、振动和重要性。重复 `createNotificationChannel()` 不会把用户已经静音的旧频道恢复为有声。
- `NotificationChannel.setLockscreenVisibility()` 只能声明该频道在锁屏上完整显示、脱敏显示或不显示。系统锁屏通知总开关、用户隐私设置和厂商策略仍然具有最终控制权。
- 官方建议在检测到频道设置妨碍预期行为时，读取 `getImportance()`/`getSound()` 等状态并提示用户修改；可用 `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` 精确打开该频道设置页。

对本项目的直接要求：

1. 检查通知权限、频道重要性、频道声音、系统勿扰/静音状态，并在设置页提供频道设置跳转。
2. 旧测试安装曾创建过同名静音频道时，不能假定重新创建会恢复声音；应迁移到新的版本化频道 ID（同时解释用户需要确认系统频道声音），或引导用户打开旧频道设置。
3. `VISIBILITY_PUBLIC` 只能使通知具备锁屏公开显示的资格，不能强制打开用户的锁屏通知显示方式。

### 1.3 锁屏与通知声音的边界

来源：Android Developers 通知总览，<https://developer.android.com/develop/ui/views/notifications?hl=en>；`NotificationChannel` API 参考，<https://developer.android.com/reference/android/app/NotificationChannel>（官方文档，访问 2026-10-06）。

官方 API 将“频道是否允许锁屏显示”与“设备/用户是否允许锁屏通知”分开处理。因此，应用可以设置 `VISIBILITY_PUBLIC`、公开标题和内容，但不能绕过系统隐私设置、勿扰模式、媒体/通知音量、厂商的后台限制来保证亮屏或锁屏一定响铃。真实手机上的最终声音必须在该手机的系统设置和静音状态下验收。

### 1.4 Doze、AlarmManager 和精确闹钟

来源：Android Developers，**Schedule alarms**，<https://developer.android.com/develop/background-work/services/alarms?hl=en>（官方文档，访问 2026-10-06）；**Optimize for Doze and App Standby**，<https://developer.android.com/training/monitoring-device-state/doze-standby?hl=en>。

- Doze 在设备静置、未充电、屏幕关闭一段时间后限制网络和 CPU；普通作业、同步和标准闹钟会被延后到维护窗口。
- `setAndAllowWhileIdle()` 允许低电耗期间触发不精确闹钟，但不是严格按秒触发；Android 12+ 的不精确闹钟可能在触发时间后最多约一小时内执行，具体还受省电限制影响。
- `setExactAndAllowWhileIdle()` 可在低电耗期间接近指定时间触发，适合用户明确打开“精确提醒”的场景；精确闹钟会消耗更多电量，官方建议能用不精确闹钟时不要使用精确闹钟。
- 目标 Android 12+ 的精确闹钟需要 `SCHEDULE_EXACT_ALARM` 或符合限定用途的 `USE_EXACT_ALARM`。`SCHEDULE_EXACT_ALARM` 是“闹钟与提醒”特殊访问权限，需要用户授予；应用应在调用前检查 `AlarmManager.canScheduleExactAlarms()`。权限被撤销时，系统会取消未来精确闹钟，应用需要重新安排。
- 官方建议把长期/较重的工作放进 `WorkManager` 或 `JobScheduler`，由闹钟接收器只启动短任务；不要在每个闹钟里直接做大规模网络抓取。

对本项目的直接要求：普通提醒用 WorkManager 或 `setAndAllowWhileIdle()`；只有用户在设置中开启精确模式时才请求特殊权限并使用 `setExactAndAllowWhileIdle()`。设备重启、权限恢复和时间变化后都要重新安排提醒。锁屏通知无声不能仅靠换 AlarmManager 方法修复，还必须同时满足通知权限、频道声音、设备通知音量、勿扰和锁屏显示条件。

## 2. 学习通课程、作业列表和详情页证据

### 2.1 证据范围和限制

学习通没有在公开开发者文档中承诺本项目使用的这些页面端点。下述结论来自真实登录会话下的只读页面复测、项目本地联调日志和页面解析器中的实际结构；它们可用于回归测试，但不能当作长期稳定 API 合同。请求只能携带用户在官方页面取得的会话，禁止在日志中输出 Cookie 或密码。

### 2.2 课程列表入口

本项目实测的官方域名入口为：

```text
GET https://mooc1-api.chaoxing.com/mycourse/backclazzdata
```

成功响应是课程 JSON。2026-10-06 的真实会话复测日志显示，响应 HTTP 200，解析出 56 门课程；课程节点中的 `courseSquareUrl`/相关字段提供 `courseId`、`classId`/`clazzid`、`cpi` 等后续请求参数。课程数据中的 `id` 不能一律当作 `courseId`，必须优先从课程入口 URL 或明确命名字段取值。

**会话失效证据：** 当 Cookie 失效时，同一请求出现 HTTP 302，随后跳转 `https://passport2.chaoxing.com/login?...` 并返回登录 HTML。此时不能把登录页当成“课程为空”或“所有作业都未知”，应标记为会话过期并要求用户重新验证。

### 2.3 作业列表入口和空课程

课程作业接口（登录会话）实测为：

```text
GET https://mooc1-api.chaoxing.com/work/task-list?courseId={courseId}&classId={classId}&cpi={cpi}
```

不同课程/客户端请求头可能导致接口返回 JSON、HTML 壳页面或拦截/登录页；HTTP 200 不等于一定包含作业。成功判断应检查响应内容是否为作业页面特征，并记录来源。

网页课程入口通常还会加载：

```text
GET https://mooc1.chaoxing.com/mooc2/work/list?courseId=...&classId=...&cpi=...&ut=s&...
```

真实页面的作业行结构（已脱敏）为：

```html
<li onclick="goTask(this);"
    data="https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?...">
    ...作业名称/状态/时间...
</li>
```

因此作业唯一入口应优先使用 `data`/`href` 中的官方跳转 URL，并从 URL 中保留 `taskrefId`/`workId` 等真实参数；不能为缺少参数的作业自行猜 ID。

网页明确显示“暂无作业”的课程是合法的空课程，不是抓取失败。解析器应把它记为成功读取但作业数为 0，不能因此把整批课程标记为验证码或会话错误。

### 2.4 作业详情页和截止时间

点击每条作业后，官方详情页 URL 形态为：

```text
https://mooc1.chaoxing.com/mooc-ans/mooc2/work/dowork?courseId=...&classId=...&workId=...&answerId=...&enc=...
```

真实详情页可见字段（页面文本，已脱敏）为：

```text
作答时间：
09-24 16:00
至
09-30 20:00
```

复测的模拟电子技术 4 条作业、复变函数与积分变换 5 条作业均在详情页得到明确开始/截止时间；单片机课程 4 条作业也得到明确截止时间。电路原理（1）页面显示“暂无作业”，不是详情页解析失败。

截止时间解析应按以下优先级：

1. 详情页“作答时间：开始 至 截止”的第二个日期；
2. 详情页中明确命名的 `endTime`/`deadline` 等字段；
3. 列表页明确标注的截止时间；
4. 只有存在明确的“剩余 X 天/小时/分钟”或“已截止”文本时才按页面抓取时刻换算。

无年份的 `MM-DD HH:mm` 必须结合详情页抓取时间和开始日期推断跨年，不能简单固定为当前年份。无法从详情页取得可靠值时应保留“未知”，不得用课程活动时间、发布时间或抓取时间伪造截止时间。

### 2.5 深度抓取的会话和请求边界

详情跳转可能先经过 `taskrefId`、`msgId`、`enc_task` 等参数，再重定向到试卷/作业页。这些值来自列表行的官方 `data`/`href` 属性；深度检查应逐条沿着官方链接 GET 详情页，遵守同一会话 Cookie 和重定向链。不能仅凭课程列表接口的摘要字段宣称已取得准确截止时间。

建议每次深度检查记录以下非敏感审计字段：课程稳定键、作业标题、来源 URL 的域名和路径（去除敏感查询值）、HTTP 状态、是否检测到登录页、是否检测到“作答时间”、解析出的时间类型（带年份/无年份/相对时间/已截止）和解析结果。绝不记录完整 `Cookie`、密码、`enc` 或其他可复用会话令牌。

## 3. 对当前问题的结论

1. “锁屏无声音”不能由应用单方面保证。必须在真实目标手机检查 Android 13+ 通知授权、频道声音/重要性、通知音量、勿扰模式、电池策略和锁屏通知显示；应用只能检测并引导设置。
2. “课程越来越多未知截止时间”首先要区分三类：会话失效/登录页、合法空课程、确实需要逐条打开详情的作业。只有第三类才进入深度检查；前两类不能被混为解析失败。
3. 真实课程详情证明逐条打开详情页可以取得可靠时间，但某些课程可能没有作业、页面改版或需要重新验证。因此产品应允许部分成功、保留旧缓存，并对仍未知的作业列出课程和作业名称供人工确认。
4. 本文引用的是 Android 官方文档和学习通真实页面证据，不是对学习通未公开接口的稳定性承诺。任何上线前版本都应保留会话失效检测、单课失败隔离、限速和手动重新检查入口。
