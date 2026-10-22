package com.zhizhou.xxtassistant.data

import java.text.SimpleDateFormat
import java.util.Locale

/** 只负责从学习通作业列表/详情 HTML 中提取截止时间，便于离线回归测试。 */
object DeadlineParser {

    fun extractFromDetail(html: String): Long = extractFromDetail(html, System.currentTimeMillis())

    /**
     * The web detail page often omits the year and renders only
     * `09-24 16:00 至 09-30 20:00`. The year is not missing data in that
     * page: it is implied by the current course session. Keep the clock
     * injectable so this rule has a deterministic regression test.
     */
    fun extractFromDetail(html: String, now: Long): Long {
        if (html.isEmpty()) return 0L
        val plain = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("&#(\\d+);")) {
                it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value
            }
            .replace(Regex("\\s+"), " ")

        // 学习通常见格式：作答时间：开始 至 截止；截止时间是第二个日期。
        val answerWindowRe = Regex(
            """作答时间\s*[:：]?\s*(\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}(?:日)?(?:\s*\d{1,2}[:时点]\d{1,2}(?:分)?)?)\s*(?:至|到|~|-)\s*(\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}(?:日)?(?:\s*\d{1,2}[:时点]\d{1,2}(?:分)?)?)""",
            RegexOption.IGNORE_CASE
        )
        answerWindowRe.find(plain)?.groupValues?.getOrNull(2)?.let { raw ->
            parseDateText(raw)?.let { if (it > 0L) return it }
        }

        val shortAnswerWindowRe = Regex(
            """作答时间\s*[:：]?\s*(\d{1,2})[-/.月](\d{1,2})(?:日)?(?:\s*(\d{1,2})[:时点](\d{1,2})(?:分)?)?\s*(?:至|到|~|-)\s*(\d{1,2})[-/.月](\d{1,2})(?:日)?(?:\s*(\d{1,2})[:时点](\d{1,2})(?:分)?)?""",
            RegexOption.IGNORE_CASE
        )
        shortAnswerWindowRe.find(plain)?.let { match ->
            val end = parseYearlessDate(
                month = match.groupValues[5],
                day = match.groupValues[6],
                hour = match.groupValues.getOrNull(7).orEmpty(),
                minute = match.groupValues.getOrNull(8).orEmpty(),
                now = now,
                startMonth = match.groupValues[1].toIntOrNull(),
                startDay = match.groupValues[2].toIntOrNull()
            )
            if (end > 0L) return end
        }

        val dateRe = Regex(
            """(?:截止时间|截止日期|作业截止|结束时间|截止|deadline|endTime|end_time|closeTime|close_time|workEndTime|work_end_time|endDate|endDateTime|stopTime|overTime)\D{0,120}?(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})[日]?(?:\s*(\d{1,2})?[:时点](\d{1,2})(?:分)?)?""",
            RegexOption.IGNORE_CASE
        )
        for (m in dateRe.findAll(plain)) {
            val g = m.groupValues
            val s = "${g[1]}-${g[2].padStart(2, '0')}-${g[3].padStart(2, '0')} " +
                "${g.getOrNull(4).orEmpty().ifBlank { "00" }.padStart(2, '0')}:" +
                "${g.getOrNull(5).orEmpty().ifBlank { "00" }.padStart(2, '0')}"
            parseDateText(s)?.let { if (it > 0L) return it }
        }

        val relRe = Regex("""(?:剩余时间|剩余|还剩|距截止|距离截止)\D{0,20}?(?:(\d+)\s*天)?(?:\s*(\d+)\s*小时)?(?:\s*(\d+)\s*分钟)?""")
        for (m in relRe.findAll(plain)) {
            val days = m.groupValues[1].toLongOrNull() ?: 0L
            val hours = m.groupValues[2].toLongOrNull() ?: 0L
            val minutes = m.groupValues[3].toLongOrNull() ?: 0L
            if (days > 0L || hours > 0L || minutes > 0L) {
                val currentTime = now
                val deadline = currentTime + days * 86400000L + hours * 3600000L + minutes * 60000L
                if (deadline > currentTime) return deadline
            }
        }

        Regex("""(?:endTime|deadline|end_time|closeTime|close_time|workEndTime|work_end_time|endDate|endDateTime|stopTime|overTime)\s*["']?\s*[=:]\s*["']?(\d{13}|\d{10})""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { raw ->
                epochFrom(raw)?.let { if (it < now + TEN_YEARS_MS) return it }
            }
        Regex("""(?:endTime|deadline|end_time|closeTime|close_time|workEndTime|work_end_time|endDate|endDateTime|stopTime|overTime)\s*["']?\s*[=:]\s*["']([^"']{8,40})["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                parseDateText(match.groupValues[1])?.let { if (it > 0L) return it }
            }
        return 0L
    }

    private fun parseYearlessDate(
        month: String,
        day: String,
        hour: String,
        minute: String,
        now: Long,
        startMonth: Int? = null,
        startDay: Int? = null
    ): Long {
        val m = month.toIntOrNull() ?: return 0L
        val d = day.toIntOrNull() ?: return 0L
        val nowDate = java.util.Calendar.getInstance().apply { timeInMillis = now }
        var year = nowDate.get(java.util.Calendar.YEAR)
        if (startMonth != null && startDay != null && (m < startMonth || (m == startMonth && d < startDay))) {
            year++
        }
        val text = "$year-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')} " +
            "${hour.ifBlank { "00" }.padStart(2, '0')}:${minute.ifBlank { "00" }.padStart(2, '0')}"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply { isLenient = false }.parse(text)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun parseTaskEndTime(remainText: String, inner: String, now: Long = System.currentTimeMillis()): Long {
        val source = "$remainText $inner"
        // A list row often contains publication time, activity time and other dates.
        // Do not treat an arbitrary timestamp as a deadline.  Only a clearly named
        // deadline/end field may provide an absolute date or epoch value here.
        labeledEpoch(source)?.let { epoch ->
            if (epoch < now + TEN_YEARS_MS) return epoch
        }
        labeledDate(source)?.let { date ->
            if (date > 0L) return date
        }

        // Relative text is accepted only when it carries its own deadline marker.
        // For example, "发布时间：1天前" must remain unknown.
        val relativeSource = source.takeIf {
            Regex("""(?:剩余时间|剩余|还剩|距截止|距离截止)""").containsMatchIn(it)
        } ?: ""
        val dayMatch = Regex("""(\d+)\s*天""").find(relativeSource)
        val hourMatch = Regex("""(\d+)\s*(?:小时|时)""").find(relativeSource)
        val minuteMatch = Regex("""(\d+)\s*(?:分钟|分)""").find(relativeSource)
        val day = dayMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val hour = hourMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minute = minuteMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (dayMatch != null || hourMatch != null || minuteMatch != null) {
            return now + day * 86400000L + hour * 3600000L + minute * 60000L
        }
        // A status label only tells us that the task is no longer open. It does
        // not contain the actual deadline and must never be turned into a
        // fabricated timestamp (for example, `now - 1000`). Keeping this
        // value unknown lets the UI distinguish "expired with a known time"
        // from "expired, but the source did not expose its deadline".
        if (source.contains("已截止") || source.contains("已过期") || source.contains("已结束")) {
            return 0L
        }
        return 0L
    }

    /**
     * Parse a value taken from an explicitly named JSON field such as `deadline`
     * or `endTime`.  The value itself has no label, so callers must only use this
     * method after selecting a trusted deadline field; arbitrary `time` fields
     * must not be passed here.
     */
    fun parseExplicitDeadlineField(value: String, now: Long = System.currentTimeMillis()): Long {
        val raw = value.trim()
        if (raw.isEmpty()) return 0L
        epochFrom(raw)?.let { epoch ->
            if (epoch < now + TEN_YEARS_MS) return epoch
        }
        parseDateText(raw)?.let { if (it > 0L) return it }
        val relative = if (Regex("""(?:\d+)\s*(?:天|小时|时|分钟|分)""").containsMatchIn(raw)) {
            parseTaskEndTime("剩余 $raw", "", now)
        } else {
            0L
        }
        return relative
    }

    private fun labeledEpoch(source: String): Long? {
        val label = deadlineLabelPattern()
        return Regex("""$label\D{0,80}(\d{10}|\d{13})(?!\d)""", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.getOrNull(1)?.let(::epochFrom)
    }

    private fun labeledDate(source: String): Long? {
        val label = deadlineLabelPattern()
        val match = Regex(
            """$label\D{0,80}(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})[日]?(?:\s*(\d{1,2})?[:时点](\d{1,2})(?:分)?)?""",
            RegexOption.IGNORE_CASE
        ).find(source) ?: return null
        val g = match.groupValues
        val text = "${g[1]}-${g[2].padStart(2, '0')}-${g[3].padStart(2, '0')} " +
            "${g.getOrNull(4).orEmpty().ifBlank { "00" }.padStart(2, '0')}:" +
            "${g.getOrNull(5).orEmpty().ifBlank { "00" }.padStart(2, '0')}"
        return parseDateText(text)
    }

    private fun deadlineLabelPattern(): String =
        "(?:截止时间|截止日期|作业截止|结束时间|截止|deadline|endTime|end_time|closeTime|close_time|workEndTime|work_end_time|endDate|endDateTime|stopTime|overTime)"

    fun parseDateText(raw: String): Long? {
        val normalized = raw.trim()
            .replace('年', '-')
            .replace('月', '-')
            .replace('日', ' ')
            .replace('时', ':')
            .replace('点', ':')
            .replace('分', ' ')
            .replace(Regex("\\s+"), " ")
        val m = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:\s+(\d{1,2})(?::(\d{1,2}))?)?""").matchEntire(normalized)
            ?: return null
        val s = "${m.groupValues[1]}-${m.groupValues[2].padStart(2, '0')}-${m.groupValues[3].padStart(2, '0')} " +
            "${m.groupValues.getOrNull(4).orEmpty().ifBlank { "00" }.padStart(2, '0')}:" +
            "${m.groupValues.getOrNull(5).orEmpty().ifBlank { "00" }.padStart(2, '0')}"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply { isLenient = false }.parse(s)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun epochFrom(raw: String): Long? {
        val value = raw.toLongOrNull() ?: return null
        return if (raw.length == 10) value * 1000L else value
    }

    private const val TEN_YEARS_MS = 10L * 365L * 86400000L
}
