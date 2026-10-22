package com.zhizhou.xxtassistant.data

import java.util.Locale

/**
 * Guards the web-import entry point against parsing a login/menu page as a
 * timetable.  The parser itself also supports user supplied HTML/CSV files,
 * so this check intentionally lives at the WebView boundary.
 */
object CourseImportPageDetector {
    data class Decision(
        val allowed: Boolean,
        val reason: String,
        /** True when the page may become readable after AJAX/DOM rendering. */
        val retryable: Boolean = false
    )

    private val tablePattern = Regex(
        "<table\\b[^>]*>([\\s\\S]*?)</table>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val gridTableMarker = Regex(
        "(?:id|class)\\s*=\\s*[\\\"'][^\\\"']*(?:kbgrid|timetable|course.?table)[^\\\"']*[\\\"']",
        RegexOption.IGNORE_CASE
    )
    private val dayMarker = Regex(
        "(?:星期|周)\\s*[一二三四五六日天1-7]|(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)",
        RegexOption.IGNORE_CASE
    )
    private val periodMarker = Regex(
        "节次|课节|时间段|上课时间|上课节次|period|time.?slot|kbcontent|td_wrap|timetable[_-]?con",
        RegexOption.IGNORE_CASE
    )
    private val courseColumnMarker = Regex(
        "课程(?:名称|名)?|科目|course(?:\\s+name)?",
        RegexOption.IGNORE_CASE
    )
    private val courseCellMarker = Regex(
        "(?:class|id)\\s*=\\s*[\\\"'][^\\\"']*(?:td_wrap|kbcontent|timetable[_-]?con|course.?cell)[^\\\"']*[\\\"']|<[^>]+class\\s*=\\s*[\\\"'][^\\\"']*\\btitle\\b[^\\\"']*[\\\"']",
        RegexOption.IGNORE_CASE
    )
    private val dataCellMarker = Regex(
        "<td\\b[^>]*>\\s*(?:(?!</td>)[\\s\\S])+?</td>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val loginFormMarker = Regex(
        "<(?:form|input|button)\\b[^>]*(?:用户名|密码|验证码|username|password|captcha)",
        RegexOption.IGNORE_CASE
    )
    private val loginHeadingMarker = Regex(
        "<(?:title|h1|h2|h3)\\b[^>]*>[^<]*(?:登录|登陆)",
        RegexOption.IGNORE_CASE
    )

    @Suppress("UNUSED_PARAMETER")
    fun inspect(html: String, url: String? = null): Decision {
        if (html.isBlank()) return Decision(false, "当前页面没有可读取的 HTML 内容")

        val visible = visibleText(html)
        val tables = tablePattern.findAll(html).map { it.value }.toList()
        val scheduleTable = tables.firstOrNull(::looksLikeScheduleTable)
        // Authenticated portals often keep a “退出登录” link in the global
        // header. A bare “登录” word is therefore not enough to classify the
        // document as a login page; require a form, a login title/heading, or
        // the explicit unified-authentication marker.
        val isLoginPage = loginFormMarker.containsMatchIn(html) ||
            loginHeadingMarker.containsMatchIn(html) ||
            (visible.contains("统一身份认证") && visible.contains("登录"))

        // A login form may contain a copied menu/table with words such as
        // “课程” and “星期”. Only accept it when the table also has concrete
        // course-cell markup; this keeps the WebView boundary from importing
        // an authentication page as an empty timetable.
        if (scheduleTable == null || (isLoginPage && !courseCellMarker.containsMatchIn(scheduleTable))) {
            val reason = if (isLoginPage) {
                "当前是登录或身份验证页面，请先完成登录后打开个人课表"
            } else {
                "当前页面未找到可识别的课程表结构，请打开课表网格后再试"
            }
            // A non-login document can be a Zhengfang shell while its table is
            // still being populated by JavaScript. Let the WebView retry the
            // read instead of treating the first empty DOM snapshot as final.
            return Decision(false, reason, retryable = !isLoginPage)
        }

        // Do not inspect the host name, path, breadcrumb, or a school-specific title.
        // Schools expose the same timetable shape under different routes and labels.
        return Decision(true, "已识别课程表网格，可继续预览")
    }

    /**
     * Recognises the table itself rather than a particular school's URL or wording.
     * A real timetable has at least one weekday heading, one period/time heading,
     * and either course-cell markup (Zhengfang-style) or a normal course column.
     */
    private fun looksLikeScheduleTable(table: String): Boolean {
        val text = visibleText(table)
        val hasDay = dayMarker.containsMatchIn(text)
        val hasPeriod = periodMarker.containsMatchIn(table) || periodMarker.containsMatchIn(text)
        if (!hasDay || !hasPeriod) return false

        val hasCourseCells = courseCellMarker.containsMatchIn(table)
        val hasCourseColumn = courseColumnMarker.containsMatchIn(text)
        val hasGridIdentity = gridTableMarker.containsMatchIn(table)
        val hasDataCells = dataCellMarker.containsMatchIn(table)
        val hasMeaningfulData = Regex(
            "<td\\b[^>]*>([\\s\\S]*?)</td>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(table).any { match ->
            val value = visibleText(match.value)
            value.length >= 2 && !value.matches(Regex("^(?:星期|周)[一二三四五六日天]$")) &&
                !value.matches(Regex("^(?:第?\\s*)?\\d{1,2}(?:\\s*[-~至到]\\s*\\d{1,2})?\\s*节$")) &&
                !value.matches(Regex("^(?:时间段|上午|下午|晚上|早上|中午)$"))
        }
        val hasConcreteCourseCell = hasConcreteCourseCell(table)

        // Grid pages may have no explicit “课程名称” header; their .td_wrap/.title
        // cells are the reliable signal. Standard exported tables use a course column.
        return hasConcreteCourseCell ||
            (hasCourseCells && hasMeaningfulData) ||
            (hasCourseColumn && hasDataCells && hasMeaningfulData) ||
            (hasGridIdentity && hasDataCells && hasMeaningfulData)
    }

    private fun hasConcreteCourseCell(table: String): Boolean {
        val titlePattern = Regex(
            "<(?:span|div|p|a)\\b[^>]*class\\s*=\\s*[\\\"'][^\\\"']*\\btitle\\b[^\\\"']*[\\\"'][^>]*>([\\s\\S]*?)</(?:span|div|p|a)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        if (titlePattern.findAll(table).any { visibleText(it.groupValues[1]).isNotBlank() }) return true
        val markedCellPattern = Regex(
            "<(?:td|div|span)\\b[^>]*(?:kbcontent|course.?cell|timetable[_-]?con)[^>]*>([\\s\\S]*?)</(?:td|div|span)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return markedCellPattern.findAll(table).any { match ->
            val value = visibleText(match.groupValues[1])
            value.any { it.code in 0x4e00..0x9fff } &&
                !value.matches(Regex("^(?:星期|周)[一二三四五六日天]$"))
        }
    }

    private fun visibleText(html: String): String = html
        .replace(Regex("<!--.*?-->", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<script.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<style.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(Locale.ROOT)
}
