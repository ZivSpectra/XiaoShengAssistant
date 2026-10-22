package com.zhizhou.xxtassistant.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTableParserTest {
    @Test
    fun parseFileRejectsLegacyXlsAndUnknownExtensionsInsteadOfTreatingThemAsText() {
        val csv = "课程,星期,节次\n电路原理,二,5-6".toByteArray()

        val legacyExcel = CourseTableParser.parseFile("schedule.xls", csv)
        assertTrue(legacyExcel.rows.isEmpty())
        assertTrue(legacyExcel.errors.single().contains("不支持旧版 Excel"))

        val unknown = CourseTableParser.parseFile("schedule.bin", csv)
        assertTrue(unknown.rows.isEmpty())
        assertTrue(unknown.errors.single().contains("不支持的文件格式"))

        val extensionless = CourseTableParser.parseFile("schedule", csv)
        assertTrue(extensionless.rows.isEmpty())
        assertTrue(extensionless.errors.single().contains("不支持的文件格式"))
    }

    @Test
    fun parseFileSupportsDelimitedAndHtmlExtensionsCaseInsensitively() {
        val csv = "课程,星期,节次\n电路原理,二,5-6".toByteArray()
        val delimited = CourseTableParser.parseFile("schedule.CSV", csv)
        assertTrue(delimited.errors.toString(), delimited.errors.isEmpty())
        assertEquals("电路原理", delimited.rows.single().name)

        val html = "<table><tr><th>课程</th><th>星期</th><th>节次</th></tr>" +
            "<tr><td>模拟电子技术</td><td>星期一</td><td>1-2</td></tr></table>"
        val parsedHtml = CourseTableParser.parseFile("schedule.HTML", html.toByteArray())
        assertTrue(parsedHtml.errors.toString(), parsedHtml.errors.isEmpty())
        assertEquals("模拟电子技术", parsedHtml.rows.single().name)
    }

    @Test
    fun parsesChineseCsvAndRanges() {
        val result = CourseTableParser.parseDelimited(
            "学期,课程名称,教师,教室,星期,节次,周次\n" +
                "2026-2027-1,模拟电子技术,张老师,实训楼101,星期一,1-2,1-16周\n"
        )
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows.single().weekday)
        assertEquals(1, result.rows.single().startPeriod)
        assertEquals(2, result.rows.single().endPeriod)
        assertEquals(16, result.rows.single().endWeek)
    }

    @Test
    fun parsesTabSeparatedRowsAndReportsBadLines() {
        val result = CourseTableParser.parseDelimited(
            "课程\t星期\t节次\t教室\n" +
                "复变函数\t三\t3-4\t东区201\n" +
                "坏数据\t周八\t?\t未知\n"
        )
        assertEquals(1, result.rows.size)
        assertEquals(3, result.rows.single().weekday)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("第 3 行"))
    }

    @Test
    fun parsesHtmlTable() {
        val html = """
            <table><tr><th>课程</th><th>星期</th><th>节次</th><th>教室</th></tr>
            <tr><td>单片机基础</td><td>周五</td><td>7-8</td><td>实验楼 302</td></tr></table>
        """.trimIndent()
        val result = CourseTableParser.parseHtml(html)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals("单片机基础", result.rows.single().name)
        assertEquals(5, result.rows.single().weekday)
    }

    @Test
    fun parsesZhengfangGridWithMultipleBlocksAndDiscreteWeeks() {
        val html = """
            <div>2026-2027 学年第 1 学期</div>
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr>
                <th>1-2节</th>
                <td id="1-1">模拟电子技术<br/>张老师<br/>实训楼101<br/>(1-8周)</td>
                <td id="1-2">复变函数与积分变换<br/>李老师<br/>东区201<br/>(1-14周,16周)</td>
                <td></td>
              </tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(2, result.rows.size)
        val analog = result.rows.first { it.name == "模拟电子技术" }
        assertEquals(1, analog.weekday)
        assertEquals(1, analog.startPeriod)
        assertEquals(2, analog.endPeriod)
        assertEquals("张老师", analog.teacher)
        assertEquals("实训楼101", analog.room)
        val complex = result.rows.first { it.name == "复变函数与积分变换" }
        assertEquals(2, complex.weekday)
        assertEquals((1..14).toList() + 16, complex.weeks)
        assertEquals("2026-2027-1", complex.term)
    }

    @Test
    fun parsesZhengfangGridCourseCells() {
        val html = """
            <div>2026-2027 学年第 1 学期</div>
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>
              <tr>
                <td>(1-2节)1-14周,16周</td>
                <td id="1-1" class="kbcontent">模拟电子技术<br>张老师<br>实训楼101<br>(1-2节)1-14周,16周</td>
                <td></td><td></td><td></td><td></td>
              </tr>
              <tr>
                <td>(7-10节)10-13周</td>
                <td></td>
                <td id="2-2" class="kbcontent">复变函数与积分变换<br>李老师<br>东区201<br>(7-10节)10-13周</td>
                <td id="3-3" class="kbcontent">单片机基础<br>王老师<br>实验楼302<br>(3-4节)2-9周<br>电路原理实验【调】<br>赵老师<br>实验楼303<br>(7-8节)11-12周</td>
                <td></td><td></td>
              </tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(4, result.rows.size)
        val analog = result.rows.first { it.name == "模拟电子技术" }
        assertEquals(1, analog.weekday)
        assertEquals(1, analog.startPeriod)
        assertEquals(2, analog.endPeriod)
        assertTrue(analog.weeks.contains(16))
        assertEquals("实训楼101", analog.room)
        val moved = result.rows.first { it.name == "电路原理实验" }
        assertEquals("调课", moved.note)
        assertEquals(8, moved.endPeriod)
    }

    @Test
    fun parsesMixedContinuousAndDiscreteWeeks() {
        val grid = CourseTableParser.parseHtml(
            """
                <table id="kbgrid_table_0">
                  <tr><th>节次</th><th>星期一</th></tr>
                  <tr><td>1-2节</td><td>工程制图<br>(1-14周,16周)</td></tr>
                </table>
            """.trimIndent()
        )
        assertTrue(grid.errors.toString(), grid.errors.isEmpty())
        val row = grid.rows.single()
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16), row.weeks)
    }

    @Test
    fun parsesRealZhengfangNestedCourseBlocks() {
        val html = """
            <div>2026-2027学年第1学期</div>
            <table id="kbgrid_table_0">
              <tr><td>时间段</td><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>
              <tr>
                <td>上午</td><td>1 08:00<br>08:45</td>
                <td id="1-1"></td><td id="2-1"></td>
                <td id="3-1" rowspan="2">
                  <div class="timetable_con text-left">
                    <span class="title">复变函数与积分变换</span>
                    <p><span title="节/周"></span> (1-2节)1-8周</p>
                    <p><span title="上课地点"></span> 理工楼C512</p>
                    <p><span title="教师"></span> 蒋老师</p>
                  </div>
                  <div class="timetable_con text-left">
                    <span class="title">数字逻辑电路与系统</span>
                    <p><span title="节/周"></span> (3-4节)10-11周</p>
                    <p><span title="上课地点"></span> 理工楼C503</p>
                    <p><span title="教师"></span> 龙老师</p>
                  </div>
                </td>
              </tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(2, result.rows.size)
        val first = result.rows.first { it.name == "复变函数与积分变换" }
        val second = result.rows.first { it.name == "数字逻辑电路与系统" }
        assertEquals(3, first.weekday)
        assertEquals(1, first.startPeriod)
        assertEquals(2, first.endPeriod)
        assertEquals("理工楼C512", first.room)
        assertEquals(3, second.startPeriod)
        assertEquals(4, second.endPeriod)
        assertEquals("理工楼C503", second.room)
    }

    @Test
    fun parsesSanitisedLiveStyleGridWithTimeColumnsTdWrapAndRowspan() {
        val html = """
            <table id="kbgrid_table_0">
              <tr>
                <td>时间段</td><td>节次</td><td>星期一</td><td>星期二</td>
                <td>星期三</td><td>星期四</td><td>星期五</td><td>星期六</td><td>星期日</td>
              </tr>
              <tr>
                <td>上午</td><td>1 08:00<br/>08:45</td>
                <td id="1-1"></td>
                <td id="2-1" class="td_wrap" rowspan="2">
                  <div class="timetable_con text-left">
                    <span class="title">课程甲</span>
                    <p><span title="节/周"></span> (1-2节)1-8周</p>
                    <p><span title="上课地点"></span> 教学楼A101</p>
                    <p><span title="教师"></span> 教师甲</p>
                    <p><span title="教学班名称"></span> 班级甲</p>
                  </div>
                </td>
                <td id="3-1"></td><td id="4-1"></td><td id="5-1"></td>
                <td id="6-1"></td><td id="7-1"></td>
              </tr>
              <tr>
                <td>上午</td><td>2 08:55<br/>09:40</td>
                <td id="1-2"></td><td id="3-2"></td><td id="4-2"></td>
                <td id="5-2"></td><td id="6-2"></td><td id="7-2"></td>
              </tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        val course = result.rows.single()
        assertEquals("课程甲", course.name)
        assertEquals(2, course.weekday)
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.endPeriod)
        assertEquals(1, course.startWeek)
        assertEquals(8, course.endWeek)
        assertTrue(course.weeks.isEmpty())
        assertEquals("教学楼A101", course.room)
        assertEquals("教师甲", course.teacher)
    }

    @Test
    fun parsesZhengfangGridWhenTableUsesNonZeroGridId() {
        val html = """
            <div>2026-2027学年第1学期</div>
            <table id="kbgrid_table_1">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><th>5-6节</th><td></td><td class="kbcontent">电路原理<br/>张三<br/>东区201<br/>1-16周</td></tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        assertEquals("电路原理", result.rows.single().name)
        assertEquals(2, result.rows.single().weekday)
        assertEquals(5, result.rows.single().startPeriod)
        assertEquals("东区201", result.rows.single().room)
    }

    @Test
    fun usesExplicitTitleForShortCourseNameWhenTeacherAndRoomAreBeforePeriod() {
        val html = """
            <div>2026-2027学年第1学期</div>
            <table class="kbgrid_table">
              <tr><th>节次</th><th>星期一</th></tr>
              <tr><th>1-2节</th><td>
                <div class="timetable_con">
                  <span class="teacher">张三</span>
                  <span class="room">理工楼C512</span>
                  <span class="title">高等数学</span>
                  <span class="period">(1-2节)1-16周</span>
                </div>
              </td></tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        assertEquals("高等数学", result.rows.single().name)
        assertEquals("张三", result.rows.single().teacher)
        assertEquals("理工楼C512", result.rows.single().room)
    }

    @Test
    fun parsesZhengfangPersonalTableCourseBlock() {
        val html = """
            <div class="breadcrumb">选课 / 个人课表查询</div>
            <div>2026-2027 学年第 1 学期</div>
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr>
                <th>1-2节</th><td></td><td></td>
                <td id="3-1">
                  <div class="timetable_con text-left">
                    <span class="title">高等数学</span>
                    <p><span title="节/周"></span> (1-2节)1-8周</p>
                    <p><span title="上课地点"></span> 理工楼C512</p>
                    <p><span title="教师"></span> 张三</p>
                    <p><span title="教学班名称"></span> 25级一班</p>
                  </div>
                  <div class="timetable_con text-left">
                    <span class="title">电路原理</span>
                    <p><span title="节/周"></span> (1-2节)1-14周,16周</p>
                    <p><span title="上课地点"></span> 东区201</p>
                    <p><span title="教师"></span> 李老师</p>
                  </div>
                </td>
              </tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(2, result.rows.size)
        val math = result.rows.first { it.name == "高等数学" }
        assertEquals(3, math.weekday)
        assertEquals("张三", math.teacher)
        assertEquals("理工楼C512", math.room)
        val circuit = result.rows.first { it.name == "电路原理" }
        assertEquals((1..14).toList() + 16, circuit.weeks)
        assertEquals("李老师", circuit.teacher)
    }

    @Test
    fun readsSinglePeriodFromZhengfangTimeLabelWhenCellHasNoInlinePeriod() {
        val html = """
            <table id="kbgrid_table_0">
              <tr><th>时间段</th><th>星期一</th></tr>
              <tr><td>1 08:00<br/>08:45</td><td>高等数学<br/>张三<br/>理工楼C512<br/>1-16周</td></tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows.single().startPeriod)
        assertEquals(1, result.rows.single().endPeriod)
    }

    @Test
    fun expandsRowspanForContinuousPeriodsWithoutDuplicatingInlineBlocks() {
        val html = """
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><th>1-2节</th><td rowspan="2" id="1-1">模拟电子技术<br/>张老师<br/>实训楼101<br/>1-16周</td><td></td></tr>
              <tr><th>3-4节</th><td></td></tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows.single().startPeriod)
        assertEquals(4, result.rows.single().endPeriod)
    }

    @Test
    fun skipsNavigationTableBeforeScheduleTable() {
        val html = """
            <table><tr><td>首页</td><td>选课</td></tr></table>
            <table>
              <tr><th>课程名称</th><th>星期</th><th>节次</th><th>周次</th></tr>
              <tr><td>电路原理</td><td>周二</td><td>5-6</td><td>3-16</td></tr>
            </table>
        """.trimIndent()

        val result = CourseTableParser.parseHtml(html)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        assertEquals("电路原理", result.rows.single().name)
        assertEquals(3, result.rows.single().startWeek)
    }

    @Test
    fun rejectsXlsxWithExternalEntityDeclaration() {
        val maliciousSheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///this-file-must-not-be-read">]>
            <worksheet><sheetData><row r="1"><c r="A1"><v>&xxe;</v></c></row></sheetData></worksheet>
        """.trimIndent()
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write(maliciousSheet.toByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }

        val result = CourseTableParser.parseXlsx(bytes)

        assertTrue("DOCTYPE should make the workbook invalid", result.rows.isEmpty())
        assertTrue("the parser should report the rejected XML", result.errors.isNotEmpty())
    }

    @Test
    fun parsesXlsxInlineAndRichSharedStrings() {
        val sharedStrings = """
            <sst>
              <si><r><t>课程</t></r><r><t>名称</t></r></si>
              <si><t>星期</t></si><si><t>节次</t></si><si><t>周次</t></si>
              <si><t>模拟电子技术</t></si><si><t>周二</t></si><si><t>1-2</t></si><si><t>3-16周</t></si>
            </sst>
        """.trimIndent()
        val sheet = """
            <worksheet><sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
              <row r="2"><c r="A2" t="inlineStr"><is><t>单片机基础</t></is></c><c r="B2" t="s"><v>5</v></c><c r="C2" t="inlineStr"><is><t>3-4</t></is></c><c r="D2" t="s"><v>7</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                listOf(
                    "xl/sharedStrings.xml" to sharedStrings,
                    "xl/worksheets/sheet1.xml" to sheet
                ).forEach { (path, xml) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(xml.toByteArray())
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

        val result = CourseTableParser.parseXlsx(bytes)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        assertEquals("单片机基础", result.rows.single().name)
        assertEquals(2, result.rows.single().weekday)
        assertEquals(3, result.rows.single().startPeriod)
        assertEquals(4, result.rows.single().endPeriod)
        assertEquals(16, result.rows.single().endWeek)
    }

    @Test
    fun parsesDiscreteAndOddTeachingWeeks() {
        val result = CourseTableParser.parseDelimited(
            "课程\t星期\t节次\t周次\n" +
                "复变函数\t一\t1-2\t1,3,5,7周\n" +
                "模拟电子\t二\t3-4\t1-16周(单周)\n"
        )
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        val schedules = CourseTableParser.toSchedules("account", result.rows, CourseSchedule.SOURCE_TABLE)
        assertEquals(2, schedules.size)
        val discrete = schedules.first { it.name == "复变函数" }
        assertTrue(discrete.occursInWeek(1))
        assertTrue(discrete.occursInWeek(7))
        assertTrue(!discrete.occursInWeek(2))
        val odd = schedules.first { it.name == "模拟电子" }
        assertTrue(odd.occursInWeek(15))
        assertTrue(!odd.occursInWeek(16))
    }

    @Test
    fun duplicateRowsProduceOneStableScheduleId() {
        val source = "课程,星期,节次,周次\n" +
            "电路原理,二,5-6,1-16周\n" +
            "电路原理,二,5-6,1-16周\n"
        val result = CourseTableParser.parseDelimited(source)
        val first = CourseTableParser.toSchedules("account", result.rows, CourseSchedule.SOURCE_TABLE)
        val second = CourseTableParser.toSchedules("account", result.rows.reversed(), CourseSchedule.SOURCE_TABLE)
        assertEquals(1, first.size)
        assertEquals(first.single().id, second.single().id)
    }

    @Test
    fun termOverrideParticipatesInStableIdentity() {
        val result = CourseTableParser.parseDelimited(
            "课程,星期,节次\n电路原理,二,5-6\n"
        )
        val autumn = CourseTableParser.toSchedules(
            "account", result.rows, CourseSchedule.SOURCE_TABLE, termOverride = "2026-2027-1"
        ).single()
        val spring = CourseTableParser.toSchedules(
            "account", result.rows, CourseSchedule.SOURCE_TABLE, termOverride = "2026-2027-2"
        ).single()
        assertTrue(autumn.id != spring.id)
    }
}
