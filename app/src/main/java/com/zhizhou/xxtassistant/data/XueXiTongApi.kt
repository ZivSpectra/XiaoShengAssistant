package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.zhizhou.xxtassistant.util.DebugLog
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 学习通数据抓取引擎。
 *
 * 【抓取原理（三层）】
 * 1. 课程列表：请求学习通官方课程列表接口（visit/courselistdata），拿 courseId/clazzid/cpi；
 *    接口万一被拦截，自动回退到解析"我的课程"网页。
 * 2. 每门课：进入课程空间页，找到"作业"模块的 iframe 地址，再请求它。
 * 3. 作业解析：用【宽松匹配】提取作业标题、截止时间、完成状态、跳转链接。
 *    —— 学习通页面结构随时可能改版，所以解析器宁可多抓、不乱猜，
 *       关键过程全部写入联调日志，改版后看一眼日志就能定位。
 *
 * 【安全红线】
 * - 只请求学习通官方域名（*.chaoxing.com）
 * - Cookie 只放在请求头里，绝不写入日志
 * - 只读数据，绝不提交作业
 */
class XueXiTongApi(private val context: Context) {

    class SessionExpiredException(message: String) : RuntimeException(message)
    class AntiSpiderException(message: String, val requestUrl: String = "") : RuntimeException(message)

    enum class SessionProbeResult {
        AUTHENTICATED,
        NEEDS_VERIFICATION,
        SESSION_EXPIRED,
        EMPTY_OR_UNKNOWN,
        NETWORK_ERROR
    }

    /**
     * 在用户明确点“完成验证”后做一次轻量会话探测。
     * 只读课程列表，不抓作业、不打开详情，用于避免把“Cookie 已写入”误报成“验证通过”。
     */
    fun probeSession(cookie: String, accountId: String): SessionProbeResult {
        return try {
            val body = httpGet(
                "https://mooc1-api.chaoxing.com/mycourse/backclazzdata",
                cookie,
                referer = "https://mooc1-api.chaoxing.com/",
                logFailures = false,
                accountId = accountId
            )
            when {
                isAntiSpiderPage(body) -> SessionProbeResult.NEEDS_VERIFICATION
                isLoginPage(body) -> SessionProbeResult.SESSION_EXPIRED
                parseCourseJson(body).isNotEmpty() -> SessionProbeResult.AUTHENTICATED
                else -> SessionProbeResult.EMPTY_OR_UNKNOWN
            }
        } catch (e: Exception) {
            DebugLog.log(context, "验证后会话探测失败：${e.javaClass.simpleName}")
            SessionProbeResult.NETWORK_ERROR
        }
    }

    // ---------- 数据结构 ----------

    /** 一门课程（课程列表接口返回的关键字段） */
    data class Course(
        val courseId: String,
        val classId: String,
        val cpi: String,
        val name: String,
        val enc: String,
        val className: String = "",
        val term: String = "",
        val ended: Boolean = false
    ) {
        val key: String get() = "$courseId:$classId"
        val displayName: String
            get() {
                val suffix = listOf(className, term).map { it.trim() }
                    .filter { it.isNotEmpty() }.distinct()
                return if (suffix.isEmpty()) name else "$name · ${suffix.joinToString(" / ")}"
            }
    }

    // ---------- 对外总入口：抓取某账号所有课程的所有作业 ----------

    /**
     * 抓取某账号所有课程的所有作业（边抓边回传，支持流式展示）。
     *
     * @param onProgress 每抓完一门课回调一次：(已完成数, 课程总数, 刚完成的课程名, 这门课是否抓成功)
     * @param onCourse   每抓完一门课回调这门课的作业列表（成功=作业列表，失败=空列表），
     *                   上层拿到后可以立刻刷新界面，不用等全部抓完
     */
    fun fetchAll(
        cookie: String,
        accountId: String,
        inspectDetails: Boolean = false,
        onProgress: (done: Int, total: Int, courseName: String, ok: Boolean) -> Unit = { _, _, _, _ -> },
        onCourse: (course: Course, homeworks: List<Homework>, ok: Boolean) -> Unit = { _, _, _ -> },
        onDetailProgress: (done: Int, total: Int, courseName: String, title: String, ok: Boolean) -> Unit = { _, _, _, _, _ -> }
    ): List<Homework> {
        val courses = fetchCourseList(cookie, accountId)
        DebugLog.log(context, "课程总数=${courses.size}")
        if (courses.isEmpty()) {
            throw SessionExpiredException("课程列表为空，学习通登录状态可能已失效")
        }

        // 课程页同时包含当前课程和已结束课程；已结束课程不再发起作业请求。
        val activeCourses = courses.filterNot { it.ended }
        DebugLog.log(context, "过滤已结束课程=${courses.size - activeCourses.size}，当前抓取=${activeCourses.size}")
        if (activeCourses.isEmpty()) return emptyList()

        // 详情核对必须串行，优先处理常见的专业课，降低一次刷新触发风控的概率。
        val orderedCourses = activeCourses.sortedWith(
            compareByDescending<Course> { detailPriority(it.name) }.thenBy { it.name }
        )
        val total = orderedCourses.size
        val done = AtomicInteger(0)
        val successfulCourses = AtomicInteger(0)
        val firstAntiSpider = AtomicReference<AntiSpiderException?>(null)
        val firstSessionExpired = AtomicReference<SessionExpiredException?>(null)
        // 逐门课先尝试接口；接口被账号风控拦截时立即切换网页课程入口。
        // 不再在正式抓取前枚举 5 组请求头，避免一次刷新制造大量无效请求。
        // 普通刷新最多两路；用户主动详情核对严格单路，确保作业详情一个一个打开。
        // 单门课程失败只记录并继续其它课程。课程列表接口已经成功，
        // 因此某门作业接口触发验证码/临时错误不能把已抓到的课程全部丢掉。
        val result = CourseBatchRunner.run(
            items = orderedCourses,
            parallelism = if (inspectDetails) 1 else minOf(2, total),
            fetch = { course ->
                ensureNotInterrupted()
                if (inspectDetails) {
                    firstAntiSpider.get()?.let { throw it }
                }
                fetchHomeworkList(cookie, course, accountId, inspectDetails, onDetailProgress)
            },
            onSuccess = { course, hw ->
                ensureNotInterrupted()
                successfulCourses.incrementAndGet()
                DebugLog.log(context, "课程[${course.name}] 抓到作业=${hw.size} 条")
                onProgress(done.incrementAndGet(), total, course.name, true)
                onCourse(course, hw, true)
            },
            onFailure = { course, error ->
                if (error is AntiSpiderException) firstAntiSpider.compareAndSet(null, error)
                if (error is SessionExpiredException) firstSessionExpired.compareAndSet(null, error)
                DebugLog.log(context, "课程[${course.name}] 抓取失败: ${error.message ?: error.javaClass.simpleName}")
                onProgress(done.incrementAndGet(), total, course.name, false)
                onCourse(course, emptyList(), false)
            }
        ).flatten()
        // 用户主动重新检查时，只要详情链路遇到验证码，就立即把验证要求
        // 交给界面；否则用户会看到“检查完成”却不知道部分详情根本没访问到。
        if (inspectDetails) {
            firstAntiSpider.get()?.let { throw it }
        }
        // 只有所有课程都失败时才把验证码/会话异常提升为整体失败。
        // 这样部分课程被风控时仍会保留已成功抓到的课程；全失败时主界面
        // 仍能打开验证页或提示重新登录。
        if (successfulCourses.get() == 0) {
            firstAntiSpider.get()?.let { throw it }
            firstSessionExpired.get()?.let { throw it }
        }
        // 按截止时间【从近到远】排序
        return result.sortedBy { it.endTime }
    }

    private fun detailPriority(name: String): Int {
        val text = name.lowercase(Locale.ROOT)
        return when {
            listOf("模电", "模拟电子", "复变", "复变函数", "单片机", "电路").any { text.contains(it) } -> 3
            listOf("高数", "数学", "大学物理", "数据结构", "程序设计").any { text.contains(it) } -> 2
            else -> 0
        }
    }

    /** 详情页返回后只刷新对应课程，避免为了更新一个状态重新抓取全部课程。 */
    fun fetchCourseHomework(cookie: String, course: Course, accountId: String): List<Homework> =
        fetchHomeworkList(cookie, course, accountId, inspectDetails = false)

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("抓取已取消")
        }
    }

    // ---------- 第 1 层：课程列表 ----------

    private fun fetchCourseList(cookie: String, accountId: String): List<Course> {
        // 官方课程列表接口（社区长期验证可用；返回 JSON）
        // 【2026-10 接口变更】旧接口 mooc1.chaoxing.com/visit/courselistdata 已废弃（带合法 Cookie 也返回 400），
        // 社区活跃库已全部迁移到 mooc1-api.chaoxing.com/mycourse/backclazzdata（纯 JSON，结构兼容 channelList）。
        val apiUrl = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata"
        val body = httpGet(apiUrl, cookie, referer = "https://mooc1-api.chaoxing.com/", accountId = accountId)
        DebugLog.log(context, "课程接口 HTTP=${body.length}字符")
        if (isLoginPage(body)) {
            throw SessionExpiredException("学习通会话已失效，请重新验证后再抓取课程")
        }
        if (isAntiSpiderPage(body)) {
            throw AntiSpiderException("学习通要求完成验证码验证", apiUrl)
        }

        // 优先解析 JSON 接口
        val fromJson = parseCourseJson(body)
        if (fromJson.isNotEmpty()) return fromJson

        // 回退：解析"我的课程"网页
        DebugLog.log(context, "JSON 解析失败，回退到网页解析")
        val page = httpGet(
            "https://mooc1.chaoxing.com/mooc-ans/mycourse",
            cookie,
            "https://i.chaoxing.com/",
            accountId = accountId
        )
        if (isLoginPage(page)) {
            throw SessionExpiredException("学习通会话已失效，请重新验证后再抓取课程")
        }
        if (isAntiSpiderPage(page)) {
            throw AntiSpiderException("学习通要求完成验证码验证", "https://mooc1.chaoxing.com/mooc-ans/mycourse")
        }
        val fromPage = parseCoursePage(page)
        if (fromPage.isEmpty()) DebugLog.log(context, "网页回退也失败，页面片段=${DebugLog.trim(page)}")
        return fromPage
    }

    private fun isAntiSpiderPage(body: String): Boolean {
        val lower = body.lowercase(Locale.ROOT)
        return lower.contains("操作异常") ||
            lower.contains("processverify") ||
            lower.contains("yzmtips") ||
            lower.contains("antispidershowverify")
    }

    /** A manually followed 302 can otherwise make a stale session look empty. */
    private fun isLoginPage(body: String): Boolean {
        val lower = body.lowercase(Locale.ROOT)
        return lower.contains("<title>登录") ||
            lower.contains("<title>登陆") ||
            (lower.contains("lg-title") && lower.contains("登录")) ||
            lower.contains("id=\"phone\"") && lower.contains("id=\"pwd\"") && lower.contains("login.js") ||
            (lower.contains("passport2.chaoxing.com") &&
                (lower.contains("loginbyp") || lower.contains("loginbypassword") || lower.contains("getauthstatus")))
    }

    /** 从课程 squareUrl 提取 courseId / classId（形如 courseId=…&personId=…&classId=…&userId=…），取不到返回空串 */
    private fun extractCourseSquare(url: String): Triple<String, String, String> {
        var courseId = ""
        var classId = ""
        var userId = ""
        if (url.isNotEmpty()) {
            Regex("courseId=(\\d+)&personId=(\\d+)").find(url)?.let { courseId = it.groupValues[1] }
            Regex("classId=(\\d+)&userId=(\\d+)").find(url)?.let {
                classId = it.groupValues[1]
                userId = it.groupValues[2]
            }
        }
        return Triple(courseId, classId, userId)
    }

    /** 解析课程列表 JSON（学习通返回形态多样：纯 JSON / HTML 包裹的 JSON / 新旧字段结构，全部兼容） */
    private fun parseCourseJson(body: String): List<Course> {
        val courses = mutableListOf<Course>()
        try {
            val root = JSONObject(extractJson(body))
            // ① 新版结构：channelList[].content.course.data[]（2026 秋季起）
            //    课程 id 在 data[].id，clazzId 在 content.id，cpi 在 channel.cpi
            val channelList = root.optJSONArray("channelList")
            if (channelList != null) {
                for (i in 0 until channelList.length()) {
                    val channel = channelList.optJSONObject(i) ?: continue
                    val content = channel.optJSONObject("content") ?: continue
                    val courseObj = content.optJSONObject("course") ?: continue
                    val data = courseObj.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        for (j in 0 until data.length()) {
                            val c = data.optJSONObject(j) ?: continue
                            // 【2026-10】新接口 backclazzdata 里 data[].id 是课程数据 ID，不是 courseId；
                            // 真正的 courseId/classId 在 courseSquareUrl（courseId=…&personId=…&classId=…&userId=…）。
                            val (squareCourseId, squareClassId, _) = extractCourseSquare(c.optString("courseSquareUrl"))
                            val courseId = firstNonBlank(
                                squareCourseId,
                                c.optString("courseId"),
                                c.optString("id")
                            )
                            if (courseId.isEmpty()) continue
                            val clazzId = firstNonBlank(
                                squareClassId,
                                content.optString("clazzId"),
                                content.optString("id")
                            )
                            val cpi = firstNonBlank(
                                channel.optString("cpi"),
                                content.optString("cpi"),
                                c.optString("cpi")
                            )
                            addCourse(courses, Course(
                                courseId = courseId,
                                classId = clazzId,
                                cpi = cpi,
                                name = firstNonBlank(c.optString("name"), content.optString("courseName"), content.optString("name")),
                                enc = "",
                                className = firstNonBlank(
                                    content.optString("className"),
                                    content.optString("clazzName"),
                                    content.optString("name")
                                ),
                                term = firstNonBlank(
                                    content.optString("term"),
                                    content.optString("semester"),
                                    c.optString("term"),
                                    c.optString("semester"),
                                    c.optString("schoolTerm")
                                ),
                                ended = CourseStatus.isEnded(System.currentTimeMillis(), c, content, channel)
                            ))
                        }
                    } else {
                        // ② 旧结构：channelList[].course[]
                        val arr = channel.optJSONArray("course") ?: continue
                        for (j in 0 until arr.length()) {
                            val c = arr.optJSONObject(j) ?: continue
                            parseCourseObj(c)?.let { addCourse(courses, it) }
                        }
                    }
                }
            }
            // ③ 兜底：全 JSON 里找含 courseId/clazzId/cpi 的对象
            if (courses.isEmpty()) {
                findCourseObjects(root).forEach { c ->
                    parseCourseObj(c)?.let { addCourse(courses, it) }
                }
            }
        } catch (e: Exception) {
            DebugLog.log(context, "课程JSON解析异常: ${e.message}")
        }
        if (courses.isNotEmpty()) {
            DebugLog.log(context, "课程解析成功，共${courses.size}门：${courses.joinToString { it.name }}")
        }
        return courses
    }

    /** 学习通可能返回 HTML 包裹的 JSON（value='{...}' 或页面 JS+JSON 混排），把最外层 JSON 提取出来 */
    private fun extractJson(body: String): String {
        val t = body.trim()
        if (t.startsWith("{")) return t
        // 课程 JSON 必含 "channelList"：从该关键字向前找真正的 JSON 起点
        // （HTML 里可能有 JS 代码块花括号，不能从第一个 { 开始）
        val marker = t.indexOf("\"channelList\"")
        if (marker >= 0) {
            val start = t.lastIndexOf('{', marker)
            val end = t.lastIndexOf('}')
            if (start >= 0 && end > start) return t.substring(start, end + 1)
        }
        // 兜底：第一个 { 到最后一个 }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) return t.substring(start, end + 1)
        return t
    }

    /** 只收"课程ID+班级ID+cpi 三要素齐全"的课程，且同一课程同班级不重复 */
    private fun addCourse(list: MutableList<Course>, c: Course) {
        if (c.courseId.isEmpty() || c.classId.isEmpty() || c.cpi.isEmpty()) return
        val key = c.courseId + "_" + c.classId
        if (list.none { it.courseId + "_" + it.classId == key }) list.add(c)
    }

    private fun findCourseObjects(node: Any): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        when (node) {
            is JSONObject -> {
                if (node.has("courseId") && node.has("clazzId") && node.has("cpi")) {
                    result.add(node)
                }
                node.keys().forEach { key ->
                    node.opt(key)?.let { result += findCourseObjects(it) }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    node.opt(i)?.let { result += findCourseObjects(it) }
                }
            }
        }
        return result
    }

    private fun parseCourseObj(c: JSONObject): Course? {
        val courseId = c.optString("courseId").takeIf { it.isNotEmpty() } ?: return null
        val classId = c.optString("clazzId").takeIf { it.isNotEmpty() } ?: return null
        val cpi = c.optString("cpi").takeIf { it.isNotEmpty() } ?: return null
        return Course(
            courseId = courseId,
            classId = classId,
            cpi = cpi,
            name = c.optString("name"),
            enc = c.optString("enc"),
            className = firstNonBlank(c.optString("className"), c.optString("clazzName"), c.optString("classNameStr")),
            term = firstNonBlank(c.optString("term"), c.optString("semester"), c.optString("schoolTerm")),
            ended = CourseStatus.isEnded(System.currentTimeMillis(), c)
        )
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() } ?: ""

    /** 回退解析："我的课程"页面（新版直接返回 JSON，先走 JSON 解析；再退到 HTML 链接提取） */
    private fun parseCoursePage(html: String): List<Course> {
        // 新版 mycouse 页面本身就是 JSON（日志里已确认），直接复用 JSON 解析
        val fromJson = parseCourseJson(html)
        if (fromJson.isNotEmpty()) return fromJson

        // 旧版：从 HTML 里提取课程链接
        val result = mutableListOf<Course>()
        // 匹配形如 studentstudy?courseId=..&clazzid=..&enc=..&cpi=.. 的链接
        val linkRe = Regex(
            """[?&](?:courseId|courseid)=(\d+)[^"']*?[?&](?:clazzid|classId)=(\d+)[^"']*?[?&](?:cpi)=(\d+)""",
            RegexOption.IGNORE_CASE
        )
        for (m in linkRe.findAll(html)) {
            result.add(
                Course(m.groupValues[1], m.groupValues[2], m.groupValues[3], "课程", "")
            )
        }
        // 尽量补课程名：找链接附近最近的文本
        val nameRe = Regex("""<a[^>]+href=["'][^"']*studentstudy[^"']*["'][^>]*>([^<]{2,60})</a>""", RegexOption.IGNORE_CASE)
        val names = nameRe.findAll(html).map { it.groupValues[1].trim() }.toList()
        result.forEachIndexed { i, course ->
            if (i < names.size && names[i].isNotEmpty()) {
                result[i] = course.copy(name = names[i])
            }
        }
        return result
    }

    // ---------- 第 2、3 层：单门课程的作业列表 ----------

    /**
     * 作业接口请求变体。task-list 在不同 Cookie/网络环境下对请求头要求不同：
     * 有的环境必须带 com.chaoxing.mobile（社区经验），有的环境带手动 Host 反而触发
     * 网关 400（通用错误页）。首次抓取时对第一门课自动探测，成功配置持久化复用。
     */
    private data class WorkVariant(
        val name: String,
        val base: String,
        val referer: String,
        val extra: Map<String, String>
    )

    private val workPrefs: SharedPreferences
        get() = context.getSharedPreferences("xxt_work_cfg", Context.MODE_PRIVATE)

    private val WORK_VARIANTS = listOf(
        // A/B/C/D 覆盖"浏览器 Cookie 配浏览器形态 / 配 App 形态"两种假设；
        // mooc2-api 域经实测不存在（连接失败），不再列入。
        WorkVariant("A_pure", "https://mooc1-api.chaoxing.com/work/task-list", "", emptyMap()),
        WorkVariant("B_xrw", "https://mooc1-api.chaoxing.com/work/task-list", "https://mooc1-api.chaoxing.com/",
            mapOf("X-Requested-With" to "com.chaoxing.mobile")),
        WorkVariant("C_no_host", "https://mooc1-api.chaoxing.com/work/task-list", "",
            mapOf("X-Requested-With" to "com.chaoxing.mobile", "Upgrade-Insecure-Requests" to "1", "accept-language" to "zh_CN")),
        WorkVariant("D_full_no_ref", "https://mooc1-api.chaoxing.com/work/task-list", "",
            mapOf("X-Requested-With" to "com.chaoxing.mobile", "Host" to "mooc1-api.chaoxing.com",
                "Upgrade-Insecure-Requests" to "1", "accept-language" to "zh_CN")),
        // E：浏览器 Cookie 配网页来源 Referer（学习通网页版作业入口）
        WorkVariant("E_web_ref", "https://mooc1-api.chaoxing.com/work/task-list", "https://mooc1.chaoxing.com/mooc-ans/mycourse", emptyMap())
    )

    /** 已探测成功的变体名；null 表示还没探测过 */
    private fun savedWorkVariant(): String? = workPrefs.getString("work_variant", null)

    /**
     * 作业接口自动探测：依次尝试全部变体，第一个拿到"正常作业页"（非 400/拦截页）即
     * 持久化并返回；全部失败返回 null。每个变体的失败原因都进日志，一次测完所有假设。
     */
    fun probeWorkRequest(cookie: String, course: Course, accountId: String = ""): String? {
        savedWorkVariant()?.let { return it }
        for (v in WORK_VARIANTS) {
            try {
                val url = "${v.base}?courseId=${course.courseId}&classId=${course.classId}&cpi=${course.cpi}"
                val html = httpGet(url, cookie, v.referer, v.extra, accountId = accountId)
                // 成功判定：拿到了"作业列表页"特征（每个作业行带 taskrefId；或含作业关键词），
                // 且不是网关 400 / enc 校验失败 / 温馨提示拦截页
                val isWorkPage = html.contains("taskrefId") ||
                        (html.contains("作业") && html.contains("截止"))
                if (isWorkPage && !html.contains("<title>400</title>") &&
                    !html.contains("enc校验失败") && !html.contains("温馨提示")
                ) {
                    DebugLog.log(context, "作业接口探测[${v.name}]成功 ${html.length} 字符，样本=${DebugLog.trim(html.take(160))}")
                    workPrefs.edit().putString("work_variant", v.name).apply()
                    return v.name
                }
                DebugLog.log(context, "作业接口探测[${v.name}]返回非作业页(${html.length}字符)，样本=${DebugLog.trim(html.take(160))}")
            } catch (e: Exception) {
                DebugLog.log(context, "作业接口探测[${v.name}]失败: ${e.message ?: ""}")
            }
        }
        DebugLog.log(context, "作业接口探测：全部 ${WORK_VARIANTS.size} 个变体均失败")
        return null
    }

    /** 取当前生效的作业请求变体（已探测成功或默认第一个） */
    private fun currentWorkVariant(): WorkVariant {
        val name = savedWorkVariant() ?: WORK_VARIANTS.first().name
        return WORK_VARIANTS.firstOrNull { it.name == name } ?: WORK_VARIANTS.first()
    }

    private fun fetchHomeworkList(
        cookie: String,
        course: Course,
        accountId: String,
        inspectDetails: Boolean = false,
        onDetailProgress: (done: Int, total: Int, courseName: String, title: String, ok: Boolean) -> Unit = { _, _, _, _, _ -> }
    ): List<Homework> {
        val v = currentWorkVariant()
        val workUrl = "${v.base}?courseId=${course.courseId}&classId=${course.classId}&cpi=${course.cpi}"
        val directHtml = try {
            httpGet(
                workUrl,
                cookie,
                v.referer,
                v.extra,
                logFailures = false,
                accountId = accountId
            )
        } catch (_: Exception) {
            null
        }
        var sourceLabel = "接口${v.name}"
        var sourceUrl = workUrl
        var workHtml = directHtml.orEmpty()
        if (isLoginPage(workHtml)) {
            throw SessionExpiredException("学习通会话已失效，作业列表需要重新验证")
        }
        var homeworks = if (workHtml.isNotEmpty()) {
            DebugLog.log(context, "作业接口响应(${course.name}) ${workHtml.length} 字符 [${v.name}]")
            extractHomeworks(workHtml, course, accountId, workUrl)
        } else {
            emptyList()
        }

        // 真实账号的 task-list 可能返回 400/拦截页；即使它返回了作业条目，
        // 条目链接也常是 android/mtaskmsgspecial，详情页无法在 Web Cookie 下取到
        // “作答时间”。只要存在未知截止时间，就用网页课程入口再取一次；网页条目的
        // /mooc2/work/task 链接能直接打开用户在浏览器里看到的详情页。
        if (homeworks.isEmpty() || homeworks.any { it.endTime <= 0L }) {
            DebugLog.log(context, "切换网页课程入口补全作业：${course.name}")
            val webResult = fetchHomeworkListViaWeb(cookie, course, accountId)
            if (webResult != null) {
                val webUrl = webResult.first
                val webHtml = webResult.second
                val webHomeworks = extractHomeworks(webHtml, course, accountId, webUrl)
                if (webHomeworks.isNotEmpty()) {
                    sourceLabel = "网页"
                    sourceUrl = webUrl
                    workHtml = webHtml
                    homeworks = webHomeworks
                    DebugLog.log(context, "网页作业列表(${course.name}) ${webHtml.length} 字符，解析=${webHomeworks.size} 条")
                } else {
                    DebugLog.log(context, "网页作业列表(${course.name})未解析到条目，保留接口结果")
                }
            }
        }

        if (homeworks.isEmpty()) {
            DebugLog.log(context, "作业解析为空(${course.name})，来源=$sourceLabel 页面样本=${DebugLog.trim(workHtml)}")
            return homeworks
        }
        // 详情页核对是用户点击“重新检查”时才执行的主动操作，普通打开只读列表接口。
        // 主动重新检查是用户明确触发的深度核对：所有作业都走详情页，
        // 这样已完成作业的旧/错误日期也不会继续污染缓存。
        val detailTargets = homeworks.filter {
            HomeworkCheckPolicy.needsDetailCheck(it, System.currentTimeMillis())
        }
        if (inspectDetails && detailTargets.isNotEmpty()) {
            DebugLog.log(context, "用户主动重新检查：逐条打开 ${detailTargets.size} 条未完成作业详情…")
            var fixed = 0
            var checked = 0
            for ((index, hw) in detailTargets.withIndex()) {
                var success = false
                try {
                    val (detailUrl, detailHtml, deadline) = fetchDetailWithCandidates(
                        hw.url, cookie, sourceUrl, accountId
                    ) ?: Triple(hw.url, "", 0L)
                    if (isAntiSpiderPage(detailHtml)) throw AntiSpiderException("详情页要求完成验证码验证", detailUrl)
                    if (deadline > 0L) {
                        fixed++
                        success = true
                        homeworks = homeworks.map {
                            if (it.id == hw.id) {
                                it.copy(endTime = deadline, officialEndTime = deadline)
                            } else it
                        }
                        DebugLog.log(context, "补取到时间：${hw.title} → ${
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(deadline)
                        }")
                    } else {
                        DebugLog.log(context, "补取无时间：${hw.title} url=$detailUrl 页面=${DebugLog.trim(detailHtml)}")
                    }
                } catch (e: AntiSpiderException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.log(context, "补取失败：${hw.title} ${e.message ?: ""}")
                }
                checked++
                onDetailProgress(checked, detailTargets.size, course.displayName, hw.title, success)
                if (index < detailTargets.lastIndex) {
                    Thread.sleep(350L)
                    ensureNotInterrupted()
                }
            }
            DebugLog.log(context, "详情页核对完成：得到可靠时间 $fixed/${detailTargets.size} 条")
        }
        return homeworks
    }

    /** 详情链接在不同端可能使用 mooc1-api、mooc1 或 mooc2-ans，按候选逐个尝试。 */
    private fun fetchDetailWithCandidates(
        url: String,
        cookie: String,
        referer: String,
        accountId: String
    ): Triple<String, String, Long>? {
        val decoded = url.replace("&amp;", "&").trim()
        // 列表条目真正的详情链路不是静态 task-work 壳页：先进入
        // android/mtaskmsgspecial，服务端再根据隐藏参数生成 doHomeWork 试卷页。
        // 该入口只执行 GET，不提交答案；它也是网页版点击作业时使用的官方链路。
        fetchOfficialWorkPaper(decoded, cookie, accountId)?.let { return it }
        val candidates = linkedSetOf(
            decoded,
            decoded.replace("mooc1-api.chaoxing.com", "mooc2-ans.chaoxing.com"),
            decoded.replace(
                "mooc1-api.chaoxing.com/mooc-ans/work/phone/task-work",
                "mooc2-ans.chaoxing.com/mooc2/work/task"
            ),
            decoded.replace("mooc1.chaoxing.com", "mooc2-ans.chaoxing.com")
        ).filter { it.startsWith("https://") }
        var fallbackDetail: Triple<String, String, Long>? = null
        for (candidate in candidates) {
            try {
                val html = httpGet(
                    candidate,
                    cookie,
                    referer = referer,
                    extraHeaders = mapOf("accept-language" to "zh-CN,zh;q=0.9"),
                    logFailures = false,
                    accountId = accountId
                )
                if (isLoginPage(html)) {
                    throw SessionExpiredException("学习通会话已失效，作业详情需要重新验证")
                }
                if (isAntiSpiderPage(html)) return Triple(candidate, html, 0L)
                val deadline = DeadlineParser.extractFromDetail(html)
                if (deadline > 0L) {
                    return Triple(candidate, html, deadline)
                }
                // 某些端点只返回作业壳页面，不含“作答时间”。继续尝试其它官方端点，
                // 最后仍没有时间时才把壳页面交给上层记录为“需人工确认”。
                if (fallbackDetail == null && looksLikeHomeworkDetail(html)) {
                    fallbackDetail = Triple(candidate, html, 0L)
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: SessionExpiredException) {
                throw e
            } catch (_: Exception) {
                // 尝试下一个同一作业的官方详情地址。
            }
        }
        return fallbackDetail
    }

    /**
     * 按学习通网页版/移动端的真实作业进入流程读取详情。
     * taskrefId、msgId、enc_task 等参数来自 task-list 的 data/href 属性。
     * 入口页可能只返回表单壳，因此继续读取隐藏的 cpi、workAnswerId、enc，
     * 再 GET doHomeWork；任何一步缺参数都回退给旧候选链，不会伪造截止时间。
     */
    private fun fetchOfficialWorkPaper(
        taskUrl: String,
        cookie: String,
        accountId: String
    ): Triple<String, String, Long>? {
        val uri = try { Uri.parse(taskUrl) } catch (_: Exception) { return null }
        val taskRefId = uri.getQueryParameter("taskrefId") ?: uri.getQueryParameter("taskRefId") ?: return null
        val courseId = uri.getQueryParameter("courseId") ?: uri.getQueryParameter("courseid") ?: return null
        val classId = uri.getQueryParameter("classId") ?: uri.getQueryParameter("clazzId") ?: uri.getQueryParameter("clazzid") ?: return null
        val userId = uri.getQueryParameter("userId") ?: ""
        val msgId = uri.getQueryParameter("msgId") ?: "0"
        val enterType = uri.getQueryParameter("type") ?: ""
        val encTask = uri.getQueryParameter("enc_task") ?: uri.getQueryParameter("encTask") ?: ""
        if (encTask.isBlank() && userId.isBlank()) return null
        val enterUrl = Uri.parse("https://mooc1-api.chaoxing.com/android/mtaskmsgspecial")
            .buildUpon()
            .appendQueryParameter("taskrefId", taskRefId)
            .appendQueryParameter("msgId", msgId)
            .appendQueryParameter("courseId", courseId)
            .appendQueryParameter("userId", userId)
            .appendQueryParameter("clazzId", classId)
            .appendQueryParameter("type", enterType)
            .appendQueryParameter("enc_task", encTask)
            .build().toString()
        return try {
            val enterHtml = httpGet(
                enterUrl, cookie, referer = taskUrl,
                extraHeaders = mapOf("X-Requested-With" to "com.chaoxing.mobile", "accept-language" to "zh-CN,zh;q=0.9"),
                logFailures = false, accountId = accountId
            )
            if (isLoginPage(enterHtml)) {
                throw SessionExpiredException("学习通会话已失效，作业详情需要重新验证")
            }
            if (isAntiSpiderPage(enterHtml)) return Triple(enterUrl, enterHtml, 0L)
            val directDeadline = DeadlineParser.extractFromDetail(enterHtml)
            if (directDeadline > 0L) return Triple(enterUrl, enterHtml, directDeadline)
            val cpi = extractHiddenValue(enterHtml, "cpi", "currentCpi")
            val answerId = extractHiddenValue(enterHtml, "workAnswerId", "answerId", "testUserRelationId")
            val enc = extractHiddenValue(enterHtml, "enc")
            if (cpi.isBlank() || answerId.isBlank() || enc.isBlank()) return null
            val paperUrl = Uri.parse("https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doHomeWork")
                .buildUpon()
                .appendQueryParameter("courseId", courseId)
                .appendQueryParameter("workId", taskRefId)
                .appendQueryParameter("cpi", cpi)
                .appendQueryParameter("workAnswerId", answerId)
                .appendQueryParameter("classId", classId)
                .appendQueryParameter("oldWorkId", "")
                .appendQueryParameter("mooc", "1")
                .appendQueryParameter("msgId", msgId)
                .appendQueryParameter("source", "0")
                .appendQueryParameter("checkIntegrity", "true")
                .appendQueryParameter("enc", enc)
                .appendQueryParameter("keyboardDisplayRequiresUserAction", "1")
                .build().toString()
            val paperHtml = httpGet(
                paperUrl, cookie, referer = enterUrl,
                extraHeaders = mapOf(
                    "X-Requested-With" to "com.chaoxing.mobile",
                    "Upgrade-Insecure-Requests" to "1",
                    "accept-language" to "zh_CN"
                ), logFailures = false, accountId = accountId
            )
            if (isLoginPage(paperHtml)) {
                throw SessionExpiredException("学习通会话已失效，作业详情需要重新验证")
            }
            if (isAntiSpiderPage(paperHtml)) return Triple(paperUrl, paperHtml, 0L)
            val deadline = DeadlineParser.extractFromDetail(paperHtml)
            Triple(paperUrl, paperHtml, deadline)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log(context, "官方作业详情链路失败 task=$taskRefId: ${e.message ?: ""}")
            null
        }
    }

    /** 从隐藏 input 或脚本赋值中读取指定字段，兼容属性顺序和单双引号。 */
    private fun extractHiddenValue(html: String, vararg names: String): String {
        for (name in names) {
            val quoted = Regex(
                """<(?:input|textarea)\b(?=[^>]*(?:id|name)\s*=\s*[\"']${Regex.escape(name)}[\"'])[^>]*\bvalue\s*=\s*[\"']([^\"']*)[\"']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!quoted.isNullOrBlank()) return quoted
            val script = Regex(
                """(?:[\"']${Regex.escape(name)}[\"']|\b${Regex.escape(name)}\b)\s*[:=]\s*[\"']([^\"']+)[\"']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!script.isNullOrBlank()) return script
        }
        return ""
    }

    private fun looksLikeHomeworkDetail(html: String): Boolean {
        val plain = html.replace(Regex("(?is)<[^>]+>"), " ")
        return plain.contains("作答时间") || plain.contains("提交作业") || plain.contains("作业要求")
    }

    /**
     * 网页降级链路：先访问课程入口让服务端生成 enc，再从课程页 iframe 中取完整作业列表 URL。
     * 返回 Pair(作业列表 URL, HTML)，失败返回 null。整个过程只读，不提交作业。
     */
    private fun fetchHomeworkListViaWeb(
        cookie: String,
        course: Course,
        accountId: String
    ): Pair<String, String>? {
        val entryUrl = "https://mooc1.chaoxing.com/visit/stucoursemiddle" +
                "?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}" +
                "&ismooc2=1&v=2"
        return try {
            val courseHtml = httpGet(
                entryUrl,
                cookie,
                referer = "https://i.chaoxing.com/",
                logFailures = false,
                accountId = accountId
            )
            if (isLoginPage(courseHtml)) {
                throw SessionExpiredException("学习通会话已失效，网页课程入口需要重新验证")
            }
            // 课程页的作业菜单不是静态 iframe，而是 course-stu.js 根据隐藏字段
            // enc/workEnc 动态拼出 /mooc2/work/list 地址。按网页端同样的参数构造，
            // 这样原生 HTTP 请求不需要执行 WebView JavaScript。
            val pageEnc = extractHiddenInput(courseHtml, "enc")
            val workEnc = extractHiddenInput(courseHtml, "workEnc")
            val pageTime = extractHiddenInput(courseHtml, "t")
            val bbsId = extractHiddenInput(courseHtml, "bbsid")
            val generatedWorkHref = if (pageEnc.isNotBlank() && workEnc.isNotBlank()) {
                buildString {
                    append("https://mooc1.chaoxing.com/mooc2/work/list")
                    append("?courseId=").append(course.courseId)
                    append("&classId=").append(course.classId)
                    append("&cpi=").append(course.cpi)
                    append("&ut=s")
                    if (pageTime.isNotBlank()) append("&t=").append(pageTime)
                    append("&stuenc=").append(pageEnc)
                    append("&enc=").append(workEnc)
                    if (bbsId.isNotBlank()) append("&bbsid=").append(bbsId)
                }
            } else null
            val iframeWorkHref = Regex(
                """<iframe[^>]+src=[\"']([^\"']*?/mooc2/work/list[^\"']*)[\"']""",
                RegexOption.IGNORE_CASE
            ).find(courseHtml)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?.let { toAbsolute(it, "https://mooc1.chaoxing.com") }
            val workHref = generatedWorkHref ?: iframeWorkHref ?: run {
                    DebugLog.log(context, "网页课程页未找到作业 iframe：${course.name} 页面=${DebugLog.trim(courseHtml)}")
                    return null
                }
            val workHtml = httpGet(
                workHref,
                cookie,
                referer = entryUrl,
                logFailures = false,
                accountId = accountId
            )
            if (isLoginPage(workHtml)) {
                throw SessionExpiredException("学习通会话已失效，网页作业列表需要重新验证")
            }
            Pair(workHref, workHtml)
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log(context, "网页作业入口失败：${course.name} ${e.message ?: "未知错误"}")
            null
        }
    }

    /** 读取课程页隐藏 input 的 value（兼容 id/name 与属性顺序变化）。 */
    private fun extractHiddenInput(html: String, id: String): String {
        val tag = Regex(
            """<input\b(?=[^>]*(?:id|name)\s*=\s*[\"']${Regex.escape(id)}[\"'])[^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value ?: return ""
        return Regex("""\bvalue\s*=\s*[\"']([^\"']*)[\"']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.trim()
            .orEmpty()
    }

    /** 从作业详情页解析截止时间：支持真实页面的“作答时间：开始至截止”字段。 */
    private fun extractHomeworks(
        html: String,
        course: Course,
        accountId: String,
        workListUrl: String
    ): List<Homework> {
        val result = mutableListOf<Homework>()

        // ① task-list 结构：每个 <li data="跳转URL"> 是一条作业，内含 <p>名称</p> 和若干 <span>状态/时间</span>
        val liRe = Regex("""<li[^>]*data\s*=\s*(['"])(.*?)\1[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
        val lis = liRe.findAll(html).toList()
        if (lis.isNotEmpty()) {
            for (m in lis) {
                val rawUrl = m.groupValues[2].trim()
                val inner = m.groupValues[3]
                if (rawUrl.isEmpty()) continue
                // 作业名优先取课程页当前结构的 overHidden2；旧版再退到第一个 p。
                val name = Regex("""<p[^>]*class=['\"][^'\"]*overHidden2[^'\"]*['\"][^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
                    .find(inner)?.groupValues?.get(1)?.let(::stripHtml)
                    ?.takeIf { it.isNotBlank() }
                    ?: Regex("""<p[^>]*>([\s\S]*?)</p>""").find(inner)
                        ?.groupValues?.get(1)?.let(::stripHtml)
                        ?.takeIf { it.isNotBlank() }
                    ?: Regex("""aria-label=[\"'][^\"']*?([^;\"']+)\s*;""", RegexOption.IGNORE_CASE)
                        .find(m.value)?.groupValues?.get(1)?.trim()
                    ?: continue
                // 新版状态使用 p.status，旧版使用 span；把可见文本交给状态/时间解析。
                val visible = stripHtml(inner)
                val statusText = Regex("""<p[^>]*class=['\"][^'\"]*status[^'\"]*['\"][^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
                    .find(inner)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
                val spans = Regex("""<span[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
                    .findAll(inner).map { stripHtml(it.groupValues[1]) }.toList()
                val status = detectTaskStatus("$statusText ${spans.firstOrNull().orEmpty()} $visible")
                val endTime = DeadlineParser.parseTaskEndTime(spans.drop(1).joinToString(" "), visible)
                // 唯一标识：taskrefId（或 workId）
                val id = Regex("""taskrefId\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(rawUrl)
                    ?.groupValues?.get(1)
                    ?: Regex("""(?:workId|workid)\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE).find(rawUrl)
                        ?.groupValues?.get(1)
                    ?: Regex("""(?:workId|workid)\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE).find(inner)
                        ?.groupValues?.get(1)
                    ?: continue
                result.add(Homework(
                    id = id,
                    accountId = accountId,
                    courseName = course.displayName,
                    title = name,
                    endTime = endTime,
                    status = status,
                    url = toAbsolute(rawUrl, "https://mooc1-api.chaoxing.com"),
                    courseKey = course.key,
                    officialEndTime = endTime.takeIf { it > 0L }
                ))
            }
            return result.distinctBy { it.id }
        }

        // ② JSON 兜底：部分课程/接口形态返回 {"data":[{...}]}
        val trimmed = html.trim()
        if (trimmed.startsWith("{")) {
            val fromJson = extractHomeworksFromJson(trimmed, course, accountId)
            if (fromJson.isNotEmpty()) return fromJson
        }

        // ③ 兜底：传统页面，按 workId 提取（老结构）
        val workIdRe = Regex("""workId\s*[=:]\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
        val ids = workIdRe.findAll(html).map { it.groupValues[1] }.distinct().toList()
        if (ids.isEmpty()) return result

        val titleRe = Regex("""<a[^>]+href=["'][^"']*work[^"']*["'][^>]*>([^<]{2,80})</a>""", RegexOption.IGNORE_CASE)
        val titles = titleRe.findAll(html).map { it.groupValues[1].trim() }.toList()
        for ((index, id) in ids.withIndex()) {
            val pos = html.indexOf(id, ignoreCase = true)
            val segment = if (pos >= 0) {
                html.substring(maxOf(0, pos - 300), minOf(html.length, pos + 300))
            } else ""
            val title = titles.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "作业$id"
            val status = detectTaskStatus(segment)
                val endTime = DeadlineParser.parseTaskEndTime(segment, "")
            val url = findWorkLink(html, id) ?: workListUrl
            result.add(Homework(
                id = id,
                accountId = accountId,
                courseName = course.displayName,
                title = title,
                endTime = endTime,
                status = status,
                url = url,
                courseKey = course.key,
                officialEndTime = endTime.takeIf { it > 0L }
            ))
        }
        return result
    }

    private fun stripHtml(value: String): String = value
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** JSON 形态的作业列表解析（{"data":[{title/name,status,deadline/workId...}]}），宽松取字段 */
    private fun extractHomeworksFromJson(
        json: String,
        course: Course,
        accountId: String
    ): List<Homework> {
        val result = mutableListOf<Homework>()
        try {
            val root = JSONObject(json)
            val data = if (root.has("data")) root.optJSONArray("data") ?: return result else return result
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("taskrefId").takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("workId").takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("id").takeIf { it.isNotBlank() && it != "null" }
                    ?: continue
                val name = item.optString("title").takeIf { it.isNotBlank() }
                    ?: item.optString("name").takeIf { it.isNotBlank() }
                    ?: item.optString("taskName").takeIf { it.isNotBlank() }
                    ?: "作业$id"
                val statusText = item.optString("status").takeIf { it.isNotBlank() }
                    ?: item.optString("statusText")
                val status = detectTaskStatus(statusText)
                val deadlineText = item.optString("deadline").takeIf { it.isNotBlank() }
                    ?: item.optString("endTime").takeIf { it.isNotBlank() }
                    ?: item.optString("time")
                val endTime = DeadlineParser.parseTaskEndTime(deadlineText, "")
                val rawUrl = item.optString("url").takeIf { it.isNotBlank() }
                    ?: item.optString("href")
                        .takeIf { it.isNotBlank() }
                    ?: "https://mooc1-api.chaoxing.com/mooc-ans/work/phone/task-work?taskrefId=$id"
                val url = toAbsolute(rawUrl, "https://mooc1-api.chaoxing.com")
                result.add(Homework(
                    id = id,
                    accountId = accountId,
                    courseName = course.displayName,
                    title = name,
                    endTime = endTime,
                    status = status,
                    url = url,
                    courseKey = course.key,
                    officialEndTime = endTime.takeIf { it > 0L }
                ))
            }
        } catch (_: Exception) {
            // JSON 解析失败就返回空，让上层记日志
        }
        return result
    }

    /** 状态文本 → 作业状态（未完成=需提醒） */
    private fun detectTaskStatus(text: String): Int = when {
        text.contains("已完成") || text.contains("已批阅") || text.contains("已通过") -> Homework.DONE
        text.contains("待批阅") || text.contains("已提交") || text.contains("已交") || text.contains("批阅中") -> Homework.SUBMITTED
        text.contains("未提交") || text.contains("未完成") || text.contains("待提交") ||
            text.contains("待重做") || text.contains("进行中") || text.contains("待做") -> Homework.PENDING
        else -> Homework.PENDING // 识别不出就当未完成，安全起见提醒
    }

    /**
     * 把"剩余 X天/小时/分钟"或具体日期换算成截止时间戳。
     * 解析不到时保留 0（未知），避免把历史作业或伪造的未来日期混入提醒。
     */
    private fun parseTaskEndTime(remainText: String, inner: String): Long {
        val now = System.currentTimeMillis()
        val source = remainText + " " + inner
        // 1) 接口可能直接返回秒/毫秒时间戳；只接受合理的日期范围，避免把作业 ID 当日期。
        Regex("""(?<!\d)(\d{10}|\d{13})(?!\d)""").find(source)?.groupValues?.get(1)?.let { raw ->
            val epoch = raw.toLongOrNull()?.let { if (raw.length == 10) it * 1000L else it }
            if (epoch != null && epoch > 0L && epoch < now + 10L * 365L * 86400000L) return epoch
        }

        // 2) 具体日期时间（兼容 2026-10-05 23:59、2026年10月5日23时59分等）
        val normalized = source
            .replace('年', '-')
            .replace('月', '-')
            .replace('日', ' ')
            .replace('时', ':')
            .replace('点', ':')
            .replace('分', ' ')
        val timeRe = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:\s+|T)?(\d{1,2})?(?::(\d{1,2}))?""")
        val t = timeRe.find(normalized)?.groupValues
        if (t != null && t[1].length == 4) {
            val s = "${t[1]}-${t[2].padStart(2, '0')}-${t[3].padStart(2, '0')} " +
                    "${(t.getOrNull(4) ?: "00").padStart(2, '0')}:${(t.getOrNull(5) ?: "00").padStart(2, '0')}"
            val epoch = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply { isLenient = false }.parse(s)?.time ?: 0L
            } catch (_: Exception) { 0L }
            if (epoch > 0) return epoch
        }
        // 3) 剩余 X 天 / X 小时 / X 分钟 → 截止 = 现在 + 剩余；0 单位也必须被识别。
        val dayMatch = Regex("""(\d+)\s*天""").find(remainText)
        val hourMatch = Regex("""(\d+)\s*(?:小时|时)""").find(remainText)
        val minuteMatch = Regex("""(\d+)\s*(?:分钟|分)""").find(remainText)
        val day = dayMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val hour = hourMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minute = minuteMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (dayMatch != null || hourMatch != null || minuteMatch != null) {
            return now + day * 86400000L + hour * 3600000L + minute * 60000L
        }
        // 4) 已截止 / 已过期
        if (remainText.contains("已截止") || remainText.contains("已过期") || remainText.contains("已结束")) {
            return now - 1000
        }
        // 5) 未知：保留未知状态，界面显示"截止时间未知"且不安排提醒。
        return 0L
    }

    /** 在页面里找包含指定 workId 的跳转链接 */
    private fun findWorkLink(html: String, workId: String): String? {
        val hrefRe = Regex("""href=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        for (m in hrefRe.findAll(html)) {
            val href = m.groupValues[1]
            if (href.contains(workId)) return toAbsolute(href, "https://mooc1.chaoxing.com")
        }
        return null
    }

    private fun toAbsolute(url: String, base: String): String {
        val candidate = when {
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("//") -> "https:" + url
            else -> base + (if (url.startsWith("/")) "" else "/") + url
        }
        val host = Uri.parse(candidate).host?.lowercase(Locale.ROOT) ?: return base
        return if (host == "chaoxing.com" || host.endsWith(".chaoxing.com")) candidate else base
    }

    /** 解析 HTTP 重定向，同时限制目标只能是学习通域名。 */
    private fun resolveRedirect(baseUrl: String, location: String): String? {
        val candidate = try {
            URL(URL(baseUrl), location).toString()
        } catch (_: Exception) {
            return null
        }
        val host = Uri.parse(candidate).host?.lowercase(Locale.ROOT) ?: return null
        return candidate.takeIf { host == "chaoxing.com" || host.endsWith(".chaoxing.com") }
    }

    // ---------- HTTP 请求 ----------

    /**
     * 会话 Cookie 罐：学习通会在首次访问 mooc1-api 域时用 Set-Cookie 下发
     * 该域的风控 token（如 cx_p_token/UID 等），后续 task-list 等接口必须携带，
     * 否则直接 HTTP 400。抓取线程池并发访问，用 ConcurrentHashMap 保证线程安全。
     */
    /**
     * 服务器下发的临时风控 Cookie 按账号隔离。旧实现把它们放在一个全局 Map，
     * 切换账号或旧任务晚到时，可能把上一个账号的 route/token 带进当前请求。
     */
    private val sessionCookiesByAccount = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /** 验证页更新 Cookie 后丢弃旧的临时 route/token，避免新旧会话混用。 */
    fun resetTransientSession(accountId: String) {
        sessionCookiesByAccount.remove(accountId)
    }

    private fun sessionCookies(accountId: String): ConcurrentHashMap<String, String> =
        sessionCookiesByAccount.getOrPut(accountId) { ConcurrentHashMap() }

    /** 合并登录 Cookie 与会话 Cookie（服务端下发的同名 token 覆盖登录值；绝不写日志） */
    private fun mergedCookie(base: String, accountId: String): String {
        // LoginActivity 会从多个学习通域收集 Cookie，同名的 route/jrose 可能出现多次。
        // 原样拼接会让服务端按第一个值解析，导致课程接口能用但 mooc2-ans 入口返回 400。
        // 这里按最后一次出现的值去重，再用本次响应刚下发的值覆盖。
        val values = LinkedHashMap<String, String>()
        base.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { seg ->
            val idx = seg.indexOf('=')
            if (idx > 0) {
                val name = seg.substring(0, idx).trim()
                values[name] = seg.substring(idx + 1).trim()
            }
        }
        sessionCookies(accountId).forEach { (name, value) -> values[name] = value }
        return values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    /** 收集响应 Set-Cookie 进会话罐（按 name 去重，忽略过期/域限定属性） */
    private fun collectSetCookies(conn: HttpURLConnection, accountId: String) {
        try {
            val headers = conn.headerFields ?: return
            for ((key, values) in headers) {
                if (key == null || !key.equals("Set-Cookie", ignoreCase = true)) continue
                for (header in values ?: emptyList()) {
                    val first = header.substringBefore(';').trim()
                    val idx = first.indexOf('=')
                    if (idx > 0) {
                        val name = first.substring(0, idx).trim()
                        val value = first.substring(idx + 1).trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) {
                            sessionCookies(accountId)[name] = value
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    /** 发起 GET 请求，带 Cookie；失败重试 1 次；绝不把 Cookie 写进日志 */
    private fun httpGet(
        urlStr: String,
        cookie: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
        logFailures: Boolean = true,
        accountId: String = ""
    ): String {
        var lastError: Exception? = null
        for (attempt in 0..1) {
            try {
                var requestUrl = urlStr
                // 不交给 HttpURLConnection 自动跟随重定向：302 的 Set-Cookie（如 route）
                // 必须先收进会话罐，再带到生成 enc 的下一跳，否则学习通会返回 HTTP 400。
                for (redirectCount in 0..4) {
                    ensureNotInterrupted()
                    val conn = URL(requestUrl).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("User-Agent", UA)
                    if (referer.isNotEmpty()) conn.setRequestProperty("Referer", referer)
                    conn.setRequestProperty("Cookie", mergedCookie(cookie, accountId))
                    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
                    extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    val code = conn.responseCode
                    if (logFailures || code in 200..299) {
                        DebugLog.log(context, "GET $requestUrl → HTTP $code")
                    }
                    // 无论成败都先收 Set-Cookie：风控 token 往往在下发时才有
                    collectSetCookies(conn, accountId)
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        val nextUrl = location?.let { resolveRedirect(requestUrl, it) }
                        if (nextUrl.isNullOrBlank()) throw RuntimeException("HTTP $code")
                        requestUrl = nextUrl
                        continue
                    }
                    if (code !in 200..299) {
                        // 非 2xx 也要看响应体：400/403 的原因（参数错误/风控/登录失效）都在 body 里
                        val errBody = try {
                            conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(500) ?: ""
                        } catch (_: Exception) { "" }
                        if (logFailures && errBody.isNotBlank()) {
                            DebugLog.log(context, "HTTP $code 响应体=${DebugLog.trim(errBody)}")
                        }
                        conn.disconnect()
                        throw RuntimeException("HTTP $code")
                    }
                    val stream = conn.inputStream
                    // 学习通部分接口返回 gzip 压缩，需解压否则是乱码
                    val encoding = conn.getHeaderField("Content-Encoding")
                    val input = if (encoding?.contains("gzip", ignoreCase = true) == true && stream != null) {
                        GZIPInputStream(stream)
                    } else {
                        stream
                    }
                    val text = input?.bufferedReader()?.use { it.readText() } ?: ""
                    conn.disconnect()
                    return text
                }
                throw RuntimeException("重定向次数过多: $urlStr")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                lastError = e
                if (logFailures) DebugLog.log(context, "GET $urlStr 失败: ${e.message}")
            }
        }
        throw RuntimeException("请求失败: $urlStr (${lastError?.message})")
    }

    companion object {
        // 模拟安卓端浏览器访问（学习通对移动端 UA 适配更好）
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
