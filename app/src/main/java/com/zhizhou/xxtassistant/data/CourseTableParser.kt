package com.zhizhou.xxtassistant.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** 无 Android 依赖的课表解析器，便于用固定文件做回归测试。 */
object CourseTableParser {

    private data class GridCell(
        val attrs: String,
        val inner: String,
        val text: String,
        val continuation: Boolean = false
    )

    data class Draft(
        val term: String,
        val name: String,
        val teacher: String,
        val room: String,
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val startWeek: Int,
        val endWeek: Int,
        val weeks: List<Int> = emptyList(),
        val weekParity: Int = 0,
        val note: String = ""
    )

    data class Result(
        val rows: List<Draft>,
        val errors: List<String>,
        /** Bell times read from the left-hand ruler of a Zhengfang grid. */
        val periodTimes: Map<Int, Pair<Int, Int>> = emptyMap()
    ) {
        val isSuccess: Boolean get() = rows.isNotEmpty() && errors.isEmpty()
    }

    private val aliases = mapOf(
        "term" to setOf("学期", "学年学期", "term"),
        "name" to setOf("课程", "课程名", "课程名称", "科目", "name", "course"),
        "teacher" to setOf("教师", "任课教师", "老师", "teacher"),
        "room" to setOf("教室", "上课地点", "上课教室", "room", "location"),
        "weekday" to setOf("星期", "周几", "星期几", "上课星期", "weekday", "day"),
        "period" to setOf("节次", "上课节次", "课节", "period", "time"),
        "startPeriod" to setOf("开始节", "起始节", "开始节次", "startperiod"),
        "endPeriod" to setOf("结束节", "终止节", "结束节次", "endperiod"),
        "week" to setOf("周次", "上课周次", "教学周", "week"),
        "startWeek" to setOf("开始周", "起始周", "startweek"),
        "endWeek" to setOf("结束周", "终止周", "endweek"),
        "note" to setOf("备注", "说明", "note")
    )

    fun parseDelimited(text: String): Result {
        val lines = text.replace("\uFEFF", "")
            .lines()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return Result(emptyList(), listOf("文件没有可读取的内容"))
        val delimiter = detectDelimiter(lines.first())
        val rows = lines.map { splitLine(it, delimiter) }
        return parseRows(rows)
    }

    /** 读取教务页面中常见的 HTML table；页面无 table 时也尝试解析制表符文本。 */
    fun parseHtml(html: String): Result {
        // 正方教务系统的“个人课表查询”不是带课程列的普通表格，而是按星期布局的
        // 网格表。先处理该结构，避免它落入标准表头解析后被误报为“缺少必要列”。
        parseZhengfangGridHtml(html)?.let { return it }
        val tablePattern = Regex("<table\\b[^>]*>(.*?)</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rowPattern = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellPattern = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val candidates = tablePattern.findAll(html).mapNotNull { table ->
            val rows = rowPattern.findAll(table.groupValues[1]).map { row ->
                cellPattern.findAll(row.groupValues[1])
                    .map { decodeHtml(it.groupValues[1].replace(Regex("<[^>]+>"), " ").trim()) }
                    .toList()
            }.filter { it.isNotEmpty() }.toList()
            rows.takeIf { it.isNotEmpty() }?.let(::parseRows)
        }.toList()
        // 教务页面常在真正课表前放导航/分页表格。只保留能解析出课程的表，
        // 不让第一个无关 table 提前返回“缺少必要列”。
        val usableTables = candidates.filter { it.rows.isNotEmpty() }
        if (usableTables.isNotEmpty()) {
            // 有些教务系统按天拆成多个 table；合并所有真正包含课程列的表，
            // 同时保留每个表中的行级错误，避免只导入第一天。
            return Result(
                rows = usableTables.flatMap { it.rows },
                errors = usableTables.flatMap { it.errors }
            )
        }
        val body = html.replace(Regex("<script.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "\t")
        return parseDelimited(decodeHtml(body))
    }

    /**
     * 解析正方教务系统的网格课表（通常为 `kbgrid_table_0`）。
     *
     * 正方不同学校会替换单元格的 class 和内部标签，但大体都遵循：第一行是星期，
     * 之后每个 td 是一个星期列，课程单元格中包含课程名、教师/教室以及
     * `(1-2节)1-8周` 这样的时间描述。这里故意只依赖文本和少量稳定标记，兼容
     * rowspan、同格多课程和带调课标记的版本。
     */
    private fun parseZhengfangGridHtml(html: String): Result? {
        val tablePattern = Regex(
            // 正方不同版本会递增表格 id（kbgrid_table_0、kbgrid_table_1），
            // 也有版本只保留 kbgrid_table class。不要把某个版本号写死。
            "<table\\b[^>]*(?:id\\s*=\\s*[\\\"']kbgrid_table(?:_\\d+)?[\\\"']|class\\s*=\\s*[\\\"'][^\\\"']*kbgrid[^\\\"']*)[^>]*>(.*?)</table>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val tables = tablePattern.findAll(html).map { it.groupValues[1] }.toList()
        if (tables.isEmpty()) return null

        // 正方页面常先保留一个空网格，再由 AJAX 将真实课程写入另一个网格。
        // 只解析第一个匹配会把仍在加载或隐藏视图误判成空课表。
        // 正方页面把“2026-2027学年第1学期”标题放在 table 外的页面头部。
        // 把整页学期传入表格解析，避免导入后课程被写入空学期。
        val pageTerm = extractTerm(html)
        val results = tables.map { table -> parseZhengfangGridTable(table, pageTerm) }
        return results.maxWithOrNull(
            compareBy<Result> { it.rows.size }.thenBy { -it.errors.size }
        )
    }

    private fun parseZhengfangGridTable(tableHtml: String, pageTerm: String = ""): Result {

        val rowPattern = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellPattern = Regex("<t[dh]\\b([^>]*)>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rawRows = rowPattern.findAll(tableHtml).map { row ->
            cellPattern.findAll(row.groupValues[1]).map { match ->
                val inner = match.groupValues[2]
                GridCell(match.groupValues[1], inner, htmlText(inner))
            }.toList()
        }.filter { it.isNotEmpty() }.toList()
        val rows = expandGridRows(rawRows)
        if (rows.isEmpty()) return Result(emptyList(), listOf("正方课表没有找到表格行"))

        val dayHeader = Regex("^(?:星期|周)(?:一|二|三|四|五|六|日|天)$")
        val dayRowIndex = rows.indexOfFirst { row ->
            val dayCount = row.count { cell -> dayHeader.matches(cell.text.trim()) }
            dayCount >= 1 && (dayCount >= 2 || row.firstOrNull()?.text?.matches(Regex("^(?:节次|时间(?:段)?|课节|周次|上课时间)$")) == true)
        }
        val dayColumns = mutableMapOf<Int, Int>()
        if (dayRowIndex >= 0) {
            rows[dayRowIndex].forEachIndexed { index, cell ->
                if (dayHeader.matches(cell.text.trim())) {
                    parseWeekday(cell.text)?.let { day -> if (day in 1..7) dayColumns[index] = day }
                }
            }
        }

        val result = mutableListOf<Draft>()
        val errors = mutableListOf<String>()
        val periodTimes = mutableMapOf<Int, Pair<Int, Int>>()
        val term = pageTerm.ifBlank { extractTerm(tableHtml) }
        rows.forEachIndexed rowLoop@{ rowIndex, row ->
            if (rowIndex == dayRowIndex) return@rowLoop
            // The live Zhengfang page contains full-width rows for the page
            // title and for unscheduled/practice-course notes.  They span the
            // whole grid and are not course cells.  Do not let their Chinese
            // text become a false "course time cannot be identified" error.
            // A full-width row that actually contains a course cell is kept;
            // this makes the guard safe for custom school templates.
            if (row.any { attributeNumber(it.attrs, "colspan") >= 7 } &&
                row.none { looksLikeCourseCell(it) &&
                    (it.inner.contains("timetable_con", ignoreCase = true) ||
                        extractGridTitles(it.inner).isNotEmpty() ||
                        periodToken.containsMatchIn(it.text))
                }
            ) return@rowLoop
            parsePeriodTimeFromRuler(row)?.let { (period, start, end) ->
                periodTimes[period] = start to end
            }
            val firstText = row.firstOrNull()?.text.orEmpty()
            val rowPeriod = parsePeriodRange(firstText)
            row.forEachIndexed cellLoop@{ columnIndex, cell ->
                // Empty grid cells still carry an id such as `1-1`; they are
                // coordinates, not courses. Never turn that id into a title.
                if (cell.text.isBlank()) return@cellLoop
                // A rowspan continuation repeats the original cell in a later
                // visual row. If the original cell already contains explicit
                // periods, parsing it again would create a false second class.
                if (cell.continuation && periodToken.containsMatchIn(cell.text)) return@cellLoop
                val day = dayColumns[columnIndex]
                    ?: gridCoordinate(cell.attrs)?.let { (first, second) ->
                        when {
                            first in 1..7 && (rowPeriod == null || second in 1..24) -> first
                            second in 1..7 -> second
                            else -> null
                        }
                    }
                if (day == null || day !in 1..7) return@cellLoop
                // 第一列通常是“节次”标尺；即使它被识别成星期，也不能当课程内容。
                if (dayRowIndex >= 0 && columnIndex == 0 && dayColumns[columnIndex] == null) return@cellLoop
                val fallbackPeriod = rowPeriod ?: periodFromGrid(cell.attrs, day)
                val drafts = parseGridCell(cell, day, fallbackPeriod, term)
                if (drafts.isEmpty() && cell.text.isNotBlank() && looksLikeCourseCell(cell)) {
                    // 有些版本把课程信息放在 title/data-* 属性而不是可见文本中；保留可定位
                    // 的错误，提示用户重新打开完整课表，而不是静默写入空数据。
                    errors += "第 ${rowIndex + 1} 行第 ${columnIndex + 1} 列的课程时间无法识别"
                } else {
                    result += drafts
                }
            }
        }
        if (result.isEmpty()) return Result(emptyList(), errors.ifEmpty { listOf("正方课表未识别到课程单元格") })
        return Result(
            rows = mergeAdjacentGridDrafts(result).distinctBy(::stableRowKey),
            errors = errors,
            periodTimes = periodTimes.toMap()
        )
    }

    /** Reads a ruler row such as `1 08:00 08:45` or `1 08:00\n08:45`. */
    private fun parsePeriodTimeFromRuler(row: List<GridCell>): Triple<Int, Int, Int>? {
        val source = row.take(2).joinToString(" ") { it.text.replace('\n', ' ') }
        val match = Regex("(?:^|\\s)(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})\\s+(\\d{1,2}):(\\d{2})(?:\\s|$)")
            .find(" $source ") ?: return null
        val period = match.groupValues[1].toIntOrNull() ?: return null
        val startHour = match.groupValues[2].toIntOrNull() ?: return null
        val startMinute = match.groupValues[3].toIntOrNull() ?: return null
        val endHour = match.groupValues[4].toIntOrNull() ?: return null
        val endMinute = match.groupValues[5].toIntOrNull() ?: return null
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        return if (period in 1..24 && start in 0 until 1440 && end in (start + 1)..1439) {
            Triple(period, start, end)
        } else null
    }

    /** Expands HTML rowspan/colspan into the rectangular grid the parser uses. */
    private fun expandGridRows(rawRows: List<List<GridCell>>): List<List<GridCell>> {
        data class Pending(val cell: GridCell, var rowsLeft: Int)
        val pending = mutableMapOf<Int, Pending>()
        val expanded = mutableListOf<List<GridCell>>()
        for (raw in rawRows) {
            val row = mutableListOf<GridCell>()
            val occupied = mutableSetOf<Int>()
            pending.entries.toList().forEach { (column, state) ->
                while (row.size <= column) row += GridCell("", "", "", continuation = true)
                row[column] = state.cell.copy(continuation = true)
                occupied += column
                if (state.rowsLeft <= 1) pending.remove(column) else state.rowsLeft--
            }
            var column = 0
            for (cell in raw) {
                while (occupied.contains(column)) column++
                val colspan = attributeNumber(cell.attrs, "colspan").coerceAtLeast(1)
                val rowspan = attributeNumber(cell.attrs, "rowspan").coerceAtLeast(1)
                for (offset in 0 until colspan) {
                    val target = column + offset
                    while (row.size <= target) row += GridCell("", "", "")
                    row[target] = if (offset == 0) cell else cell.copy(continuation = true)
                    if (rowspan > 1) pending[target] = Pending(cell, rowspan - 1)
                    occupied += target
                }
                column += colspan
            }
            expanded += row
        }
        return expanded
    }

    private fun attributeNumber(attrs: String, name: String): Int =
        Regex("\\b${Regex.escape(name)}\\s*=\\s*[\\\"'](\\d+)[\\\"']", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

    /** Joins adjacent visual rows for one rowspan course into one period range. */
    private fun mergeAdjacentGridDrafts(rows: List<Draft>): List<Draft> {
        val merged = mutableListOf<Draft>()
        rows.sortedWith(compareBy<Draft> { it.weekday }.thenBy { it.startPeriod }.thenBy { it.name }).forEach { row ->
            val index = merged.indexOfLast { previous ->
                previous.name == row.name && previous.teacher == row.teacher &&
                    previous.room == row.room && previous.weekday == row.weekday &&
                    previous.startWeek == row.startWeek && previous.endWeek == row.endWeek &&
                    previous.weeks == row.weeks && previous.weekParity == row.weekParity &&
                    row.startPeriod <= previous.endPeriod + 1
            }
            if (index >= 0) {
                val previous = merged[index]
                merged[index] = previous.copy(endPeriod = maxOf(previous.endPeriod, row.endPeriod))
            } else {
                merged += row
            }
        }
        return merged
    }

    private fun parseGridCell(
        cell: GridCell,
        day: Int,
        fallbackPeriod: Pair<Int, Int>?,
        term: String
    ): List<Draft> {
        // Attributes are only coordinates (`id="3-1"`), never course text.
        // Keeping them out of the field parser prevents an empty/metadata cell
        // from becoming a bogus course named `id="3-1"`.
        val source = cell.text
        val periodMatches = periodToken.findAll(source).toList()
        // Live Zhengfang pages expose the course name in a `.title` element.
        // Prefer that structural signal because short names such as “高等数学”
        // are indistinguishable from a two-to-four-character teacher name when
        // only flattened text is available.
        val titleNames = extractGridTitles(cell.inner)
        val segments = if (periodMatches.isNotEmpty()) {
            periodMatches.mapIndexed { index, match ->
                // 正方通常把课程名/教师/教室放在节次描述前面。每个节次描述前的
                // 文本就是一个课程块，因此同一单元格内多门课程也能自然拆开。
                val start = if (index == 0) 0 else periodMatches[index - 1].range.last + 1
                val end = periodMatches.getOrNull(index + 1)?.range?.first ?: source.length
                Triple(source.substring(start, match.range.first), match.value, source.substring(match.range.last + 1, end))
            }
        } else listOf(Triple(source, "", ""))

        return segments.mapIndexedNotNull { segmentIndex, (prefix, periodTokenText, suffix) ->
            val period = parsePeriodRange(periodTokenText) ?: fallbackPeriod ?: return@mapIndexedNotNull null
            if (period.first !in 1..24 || period.second !in period.first..24) return@mapIndexedNotNull null
            // The week expression follows the period marker on the same line.
            // Read that line directly so a second course in the same grid cell
            // cannot contribute its weeks to the current course.
            val weekInput = if (periodTokenText.isBlank()) source else suffix
            val weekLine = weekInput.substringBefore('\n').trim()
            val week = parseWeekSelectionFromGrid(weekLine)
                ?: parseWeekSelectionFromGrid(weekInput)
            val fields = gridFields(prefix)
            // For a later block, `prefix` begins after the previous period and
            // may contain the previous block's room/teacher/class before the
            // next title. Choose the last field that is neither metadata nor a
            // room/teacher; that is the course title immediately before the
            // current period marker.
            val eligibleNameIndexes = fields.mapIndexedNotNull { index, field ->
                if (isGridMetadata(field) || looksLikeRoom(field)) null else index
            }
            // Cells without an inline period marker use the row's period label;
            // their first field is the course title, even when it is a short
            // all-Chinese name that resembles a teacher name. Inline multi-
            // course blocks keep the last non-teacher field for the same reason
            // that a later block may begin with the previous block's metadata.
            val explicitName = titleNames.getOrNull(segmentIndex)
                ?.takeIf { it.isNotBlank() }
            val explicitNameIndex = explicitName?.let { title ->
                fields.indexOfLast { it.equals(title, ignoreCase = true) }
                    .takeIf { it >= 0 }
            }
            val nameIndex = explicitNameIndex ?: if (periodTokenText.isBlank()) {
                eligibleNameIndexes.firstOrNull()
            } else {
                eligibleNameIndexes.lastOrNull { !looksLikeTeacher(fields[it]) }
                    ?: eligibleNameIndexes.lastOrNull()
            } ?: return@mapIndexedNotNull null
            val name = explicitName ?: fields[nameIndex]
            if (name.isBlank() || isGridLabel(name)) return@mapIndexedNotNull null
            // Depending on the Zhengfang template, teacher/room are either
            // before the period marker (simple exports) or after it (the live
            // `timetable_con` page). Inspect both sides of the marker.
            // Metadata may be rendered before the title (some mobile templates)
            // or after it (desktop templates). Search the nearest fields on both
            // sides, preferring the suffix where the current block normally ends.
            val rest = (fields.drop(nameIndex + 1) + gridFields(suffix)) +
                fields.take(nameIndex).asReversed()
            val room = rest.firstOrNull { looksLikeRoom(it) }.orEmpty()
            val teacher = rest.firstOrNull { it != room && looksLikeTeacher(it) }
                ?: rest.firstOrNull { it != room }.orEmpty()
            val note = if (prefix.contains("调")) "调课" else ""
            Draft(
                term = term,
                name = name,
                teacher = teacher,
                room = room,
                weekday = day,
                startPeriod = period.first,
                endPeriod = period.second,
                startWeek = week?.start ?: 1,
                endWeek = week?.end ?: 20,
                weeks = week?.weeks.orEmpty(),
                weekParity = week?.parity ?: 0,
                note = note
            )
        }
    }

    private fun extractGridTitles(inner: String): List<String> = Regex(
        "<(?:span|div|p|a)\\b[^>]*\\bclass\\s*=\\s*[\\\"'][^\\\"']*\\btitle\\b[^\\\"']*[\\\"'][^>]*>(.*?)</(?:span|div|p|a)>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).findAll(inner).mapNotNull { match ->
        htmlText(match.groupValues[1]).trim().takeIf { it.isNotBlank() }
    }.toList()

    private val periodToken = Regex(
        "(?:[（(]\\s*)?(?:第\\s*)?(\\d{1,2})\\s*[-~—至到]\\s*(\\d{1,2})\\s*节(?:[）)])?",
        RegexOption.IGNORE_CASE
    )

    private fun parsePeriodRange(value: String): Pair<Int, Int>? {
        val match = periodToken.find(value) ?: return Regex("(?:第\\s*)?(\\d{1,2})\\s*节").find(value)?.let {
            val number = it.groupValues[1].toIntOrNull() ?: return@let null
            number to number
        } ?: Regex("^\\s*(\\d{1,2})\\s+\\d{1,2}:\\d{2}").find(value)?.let {
            val number = it.groupValues[1].toIntOrNull() ?: return@let null
            number to number
        }
        return match.groupValues[1].toIntOrNull()?.let { start ->
            val end = match.groupValues[2].toIntOrNull() ?: start
            start to end
        }
    }

    private fun gridCoordinate(attrs: String): Pair<Int, Int>? {
        // id/class/data-* 均见于正方不同版本，允许短横线、下划线和空格分隔。
        val match = Regex("(?<!\\d)(\\d{1,2})\\s*[-_]\\s*(\\d{1,2})(?!\\d)").find(attrs)
            ?: return null
        return match.groupValues[1].toIntOrNull()?.let { first ->
            match.groupValues[2].toIntOrNull()?.let { second -> first to second }
        }
    }

    private fun periodFromGrid(attrs: String, day: Int): Pair<Int, Int>? {
        val coordinate = gridCoordinate(attrs) ?: return null
        val period = when {
            coordinate.first == day && coordinate.second in 1..24 -> coordinate.second
            coordinate.second == day && coordinate.first in 1..24 -> coordinate.first
            coordinate.first !in 1..7 && coordinate.first in 1..24 -> coordinate.first
            coordinate.second !in 1..7 && coordinate.second in 1..24 -> coordinate.second
            else -> null
        } ?: return null
        return period to period
    }

    private fun looksLikeCourseCell(cell: GridCell): Boolean =
        gridCoordinate(cell.attrs) != null || cell.text.any { it in '\u4e00'..'\u9fff' }

    private fun htmlText(value: String): String {
        val withBreaks = value
            .replace(Regex("<(?:br|/p|/div|/li|/td|/th)\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<script.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<[^>]+>"), " ")
        return decodeHtml(withBreaks)
            .replace('\u00a0', ' ')
            .lineSequence()
            .map { it.replace(Regex("[\\t ]+"), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun extractTerm(html: String): String {
        val source = decodeHtml(Regex("<[^>]+>").replace(html, " "))
        val term = Regex("\\b(20\\d{2})\\s*[-—/]\\s*(20\\d{2})\\s*学年(?:第?\\s*([一二12])\\s*学期)?").find(source)
            ?: Regex("\\b(20\\d{2})\\s*[-—/]\\s*(20\\d{2})\\s*[-/]\\s*([12])\\b").find(source)
        return term?.let {
            val start = it.groupValues[1]
            val end = it.groupValues[2]
            val semester = it.groupValues.getOrNull(3).orEmpty().let { value ->
                when (value) { "一" -> "1"; "二" -> "2"; else -> value }
            }
            if (semester.isBlank()) "$start-$end" else "$start-$end-$semester"
        }.orEmpty()
    }

    private fun parseWeekSelectionFromGrid(value: String): WeekSelection? {
        val weekParts = Regex("\\d[\\d\\s,，、;；/／~至到-]*(?:周|单双周)").findAll(value)
            .map { it.value.trim() }
            .toList()
        if (weekParts.isEmpty()) {
            return when {
                value.contains("单周") || value.contains("奇数周") -> WeekSelection(1, 20, parity = 1)
                value.contains("双周") || value.contains("偶数周") -> WeekSelection(1, 20, parity = 2)
                else -> null
            }
        }
        val parsed = weekParts.mapNotNull(::parseWeekSelection)
        if (parsed.isEmpty()) return null
        // A Zhengfang cell may render a mixed expression as separate tokens,
        // e.g. `1-14周,16周`. Expand the continuous token only when another
        // token is present, so a plain `1-16周` keeps the compact range form.
        val explicit = if (parsed.size > 1) {
            parsed.flatMap { selection ->
                if (selection.weeks.isNotEmpty()) selection.weeks
                else (selection.start..selection.end).toList()
            }.distinct().sorted()
        } else {
            parsed.flatMap { it.weeks }.distinct().sorted()
        }
        val parity = parsed.firstOrNull { it.parity != 0 }?.parity ?: 0
        return if (explicit.isNotEmpty()) {
            WeekSelection(explicit.first(), explicit.last(), explicit, parity)
        } else {
            WeekSelection(parsed.minOf { it.start }, parsed.maxOf { it.end }, parity = parity)
        }
    }

    private fun gridFields(prefix: String): List<String> = prefix
        .replace("【调】", "")
        .replace("[调]", "")
        // 第二个课程块通常从上一个节次标记之后开始，前面会残留上一块的
        // `1-8周`；先去掉周次，避免它被误当成课程名称。
        .replace(Regex("(?<!\\d)\\d+\\s*[-~至到]\\s*\\d+\\s*周"), "\n")
        .replace(Regex("(?<![-\\d])\\d+\\s*周"), "\n")
        .split(Regex("[\\r\\n]+|\\s{2,}|[|｜]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        // A fallback-period cell can leave the brackets/commas from
        // `(1-8周)` behind after the week expression is removed. They are
        // separators, never course fields.
        .filterNot { it.all { char -> char in "()（）,，、;；" } }
        .filterNot {
            periodToken.containsMatchIn(it) ||
                it.matches(Regex("^[0-9]+[-_]?[0-9]*$")) ||
                it.matches(Regex("^[0-9]+(?:\\s*[-~至到]\\s*[0-9]+)?周(?:次)?$"))
        }

    private fun isGridMetadata(value: String): Boolean =
        parseWeekSelectionFromGrid(value) != null ||
            value.matches(Regex("^[0-9]+(?:\\.[0-9]+)?$")) ||
            value.matches(Regex("^(?:学分|重修标记|课程性质|教学班名称)[:：]?.*"))

    private fun isGridLabel(value: String): Boolean = value.matches(Regex("^(?:星期|周)[一二三四五六日天]$")) ||
        value.matches(Regex("^第?\\d{1,2}[-~至到]?\\d{0,2}节$"))

    private fun looksLikeRoom(value: String): Boolean =
        value.contains(Regex("教室|教学楼|实验楼|实训楼|楼|栋|室|区|馆")) ||
            Regex("[A-Za-z]?\\d{2,}[A-Za-z]?").containsMatchIn(value)

    private fun looksLikeTeacher(value: String): Boolean =
        value.endsWith("老师") || value.endsWith("教师") || value.endsWith("教授") ||
            value.endsWith("讲师") || value.length in 2..4 && value.all { it in '\u4e00'..'\u9fff' }

    /** 解析常见的 xlsx 第一张工作表，不依赖 Apache POI。 */
    fun parseXlsx(bytes: ByteArray): Result {
        return try {
            val entries = HashMap<String, ByteArray>()
            var totalBytes = 0L
            var entryCount = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entryCount > 128) return Result(emptyList(), listOf("XLSX 包含过多文件，无法安全导入"))
                    if (!entry.isDirectory) {
                        val content = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            totalBytes += count
                            if (entryBytes > 4L * 1024 * 1024 || totalBytes > 16L * 1024 * 1024) {
                                return Result(emptyList(), listOf("XLSX 解压内容过大，无法安全导入"))
                            }
                            content.write(buffer, 0, count)
                        }
                        entries[entry.name] = content.toByteArray()
                    }
                }
            }
            val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
            val sheet = entries.keys
                .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedWith(compareBy { Regex("sheet(\\d+)\\.xml").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE })
                .firstOrNull()
                ?.let(entries::get)
                ?: return Result(emptyList(), listOf("XLSX 中没有找到工作表"))
            parseRows(parseSheet(sheet, shared))
        } catch (e: Exception) {
            Result(emptyList(), listOf("XLSX 解析失败：${e.javaClass.simpleName}"))
        }
    }

    fun parseFile(name: String, bytes: ByteArray): Result {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "xlsx" -> parseXlsx(bytes)
            "csv", "tsv", "txt" -> parseDelimited(decodeText(bytes))
            "html", "htm" -> parseHtml(decodeText(bytes))
            "xls" -> Result(emptyList(), listOf("暂不支持旧版 Excel .xls 文件，请另存为 .xlsx 或导出为 CSV。"))
            else -> Result(emptyList(), listOf("不支持的文件格式，请选择 CSV、TSV、TXT、XLSX 或 HTML 文件。"))
        }
    }

    /** Chinese school exports are often GBK/GB18030 despite a .csv suffix. */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = bytes.toString(StandardCharsets.UTF_8)
        return if ('\uFFFD' in utf8) bytes.toString(Charset.forName("GB18030")) else utf8
    }

    fun toSchedules(
        accountId: String,
        rows: List<Draft>,
        source: Int,
        termOverride: String? = null
    ): List<CourseSchedule> =
        rows.map { row -> termOverride?.let { row.copy(term = it) } ?: row }
            .distinctBy(::stableRowKey).map { row ->
            CourseSchedule(
                id = "${source}_${stableId(stableRowKey(row))}",
                accountId = accountId,
                term = row.term,
                name = row.name,
                teacher = row.teacher,
                room = row.room,
                weekday = row.weekday,
                startPeriod = row.startPeriod,
                endPeriod = row.endPeriod,
                startWeek = row.startWeek,
                endWeek = row.endWeek,
                weeks = row.weeks,
                weekParity = row.weekParity,
                source = source,
                note = row.note
            )
        }

    private fun parseRows(rows: List<List<String>>): Result {
        if (rows.isEmpty()) return Result(emptyList(), listOf("没有找到表格行"))
        val header = rows.first().map(::normalize)
        val indexes = aliases.mapValues { (_, names) -> header.indexOfFirst { it in names.map(::normalize) } }
        if (indexes.getValue("name") < 0 || indexes.getValue("weekday") < 0 || indexes.getValue("period") < 0 &&
            (indexes.getValue("startPeriod") < 0 || indexes.getValue("endPeriod") < 0)) {
            return Result(emptyList(), listOf("缺少必要列：课程、星期、节次"))
        }
        val result = mutableListOf<Draft>()
        val errors = mutableListOf<String>()
        rows.drop(1).forEachIndexed { offset, row ->
            val line = offset + 2
            fun value(key: String): String = indexes[key]?.takeIf { it >= 0 }?.let { row.getOrNull(it).orEmpty().trim() }.orEmpty()
            val name = value("name")
            if (name.isBlank()) {
                errors += "第 $line 行缺少课程名称"
                return@forEachIndexed
            }
            val day = parseWeekday(value("weekday"))
            val periods = parseRange(value("period"))
            val start = parseInt(value("startPeriod")) ?: periods?.first
            val end = parseInt(value("endPeriod")) ?: periods?.second
            val weekSelection = parseWeekSelection(value("week"))
                ?: parseWeekSelection(value("startWeek"))?.let { parsedStart ->
                    if (value("endWeek").isBlank()) parsedStart
                    else parseWeekSelection("${value("startWeek")}-${value("endWeek")}")
                }
            if (day == null || start == null || end == null) {
                errors += "第 $line 行的星期或节次无法识别"
                return@forEachIndexed
            }
            if (start !in 1..24 || end !in start..24) {
                errors += "第 $line 行节次范围无效"
                return@forEachIndexed
            }
            result += Draft(
                term = value("term"), name = name, teacher = value("teacher"), room = value("room"),
                weekday = day, startPeriod = start, endPeriod = end,
                startWeek = weekSelection?.start ?: 1,
                endWeek = weekSelection?.end ?: 20,
                weeks = weekSelection?.weeks.orEmpty(),
                weekParity = weekSelection?.parity ?: 0,
                note = value("note")
            )
        }
        return Result(result, errors)
    }

    private fun detectDelimiter(line: String): Char = when {
        line.count { it == '\t' } >= line.count { it == ',' } && line.contains('\t') -> '\t'
        line.contains(';') && !line.contains(',') -> ';'
        else -> ','
    }

    private fun splitLine(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { value.append('"'); i++ }
                else quoted = !quoted
            } else if (c == delimiter && !quoted) {
                result += value.toString().trim(); value.clear()
            } else value.append(c)
            i++
        }
        result += value.toString().trim()
        return result
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\s_()（）【】\\[\\]：:.-]"), "")

    private fun parseInt(value: String): Int? = Regex("\\d+").find(value)?.value?.toIntOrNull()

    private fun parseRange(value: String): Pair<Int, Int>? {
        val numbers = Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isEmpty()) return null
        return numbers.first() to (numbers.getOrNull(1) ?: numbers.first())
    }

    private data class WeekSelection(
        val start: Int,
        val end: Int,
        val weeks: List<Int> = emptyList(),
        val parity: Int = 0
    )

    /**
     * Parses continuous, odd/even, and explicit teaching-week expressions.
     * Examples: 1-16周, 1,3,5,7周, 1-16周(单周), 单周, 双周.
     */
    private fun parseWeekSelection(value: String): WeekSelection? {
        val clean = value.trim()
        if (clean.isBlank()) return null
        val numbers = Regex("\\d+").findAll(clean)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..60 }
            .toList()
        val parity = when {
            clean.contains("单双") || clean.contains("单、双") -> 0
            clean.contains("单") || clean.contains("奇") -> 1
            clean.contains("双") || clean.contains("偶") -> 2
            else -> 0
        }
        if (numbers.isEmpty()) {
            return if (parity != 0) WeekSelection(1, 20, parity = parity) else null
        }
        val hasExplicitList = Regex("[,，、/／;；]").containsMatchIn(clean)
        val ranges = Regex("(\\d+)\\s*[-~至到]\\s*(\\d+)").findAll(clean)
            .mapNotNull { match ->
                val start = match.groupValues[1].toIntOrNull()
                val end = match.groupValues[2].toIntOrNull()
                if (start == null || end == null || start !in 1..60 || end !in start..60) null else start..end
            }
            .toList()
        // 正方页面经常返回“1-14周,16周”。只保留起止周会丢掉离散的第 16 周，
        // 因此混合范围和列表时展开成显式周次；连续区间仍沿用轻量的 parity 表示。
        if (hasExplicitList && ranges.isNotEmpty()) {
            val rangeNumbers = ranges.flatMap { it.toList() }
            val singles = numbers.filter { number -> ranges.none { number in it } }
            val selected = (rangeNumbers + singles).distinct().sorted()
            return WeekSelection(selected.first(), selected.last(), selected, parity)
        }
        if (hasExplicitList) {
            val selected = numbers.distinct().sorted()
            return WeekSelection(selected.first(), selected.last(), selected, parity)
        }
        if (ranges.isNotEmpty()) {
            val range = ranges.first()
            return WeekSelection(range.first, range.last, parity = parity)
        }
        return WeekSelection(numbers.first(), numbers.getOrNull(1) ?: numbers.first(), parity = parity)
    }

    private fun stableRowKey(row: Draft): String = listOf(
        row.term, row.name, row.teacher, row.room, row.weekday.toString(),
        row.startPeriod.toString(), row.endPeriod.toString(), row.startWeek.toString(),
        row.endWeek.toString(), row.weeks.sorted().joinToString(","), row.weekParity.toString(), row.note
    ).joinToString("\u0000") { it.trim().lowercase(Locale.ROOT) }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun parseWeekday(value: String): Int? {
        val clean = value.trim()
        val match = Regex("^(?:星期|周)?([一二三四五六日天1-7])$").matchEntire(clean) ?: return null
        return when (match.groupValues[1]) {
            "一", "1" -> 1
            "二", "2" -> 2
            "三", "3" -> 3
            "四", "4" -> 4
            "五", "5" -> 5
            "六", "6" -> 6
            "日", "天", "7" -> 7
            else -> null
        }
    }

    private fun decodeHtml(value: String): String = value
        .replace("&nbsp;", " ", true)
        .replace("&amp;", "&", true)
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)
        .replace("&#39;", "'", true)
        .replace("&quot;", "\"", true)

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val doc = document(xml)
        return doc.getElementsByTagName("si").asNodeSequence().map { node ->
            (node as? Element)?.getElementsByTagName("t")?.asNodeSequence()
                ?.joinToString("") { it.textContent }.orEmpty()
        }.toList()
    }

    private fun parseSheet(xml: ByteArray, shared: List<String>): List<List<String>> {
        val doc = document(xml)
        val rows = mutableListOf<List<String>>()
        val nodes = doc.getElementsByTagName("row")
        for (r in 0 until nodes.length) {
            val row = nodes.item(r) as? Element ?: continue
            val cells = sortedMapOf<Int, String>()
            val children = row.childNodes
            for (i in 0 until children.length) {
                val cell = children.item(i) as? Element ?: continue
                if (cell.tagName != "c") continue
                val col = Regex("[A-Z]+", RegexOption.IGNORE_CASE).find(cell.getAttribute("r"))?.value ?: continue
                val column = columnNumber(col)
                val value = cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                val rendered = when (cell.getAttribute("t")) {
                    "s" -> shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                    "inlineStr" -> cell.getElementsByTagName("t").asNodeSequence().joinToString("") { it.textContent }
                    else -> value
                }
                cells[column] = rendered
            }
            val max = cells.keys.maxOrNull() ?: continue
            rows += (0..max).map { cells[it].orEmpty() }
        }
        return rows
    }

    private fun columnNumber(name: String): Int = name.uppercase(Locale.ROOT).fold(0) { total, c -> total * 26 + (c - 'A' + 1) } - 1

    /**
     * Parse an XML part from an imported workbook without resolving external
     * entities. Course files are user supplied, so a normal DOM builder would
     * otherwise allow a malicious DOCTYPE to read local files or make network
     * requests while importing.
     */
    private fun document(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // Reject every DOCTYPE instead of trying to interpret an untrusted DTD.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().apply {
        // Defense in depth for parsers that do not honor one of the features.
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }.parse(ByteArrayInputStream(bytes))

    private fun org.w3c.dom.NodeList.asNodeSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (i in 0 until length) yield(item(i))
    }
}
