package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression fixture copied from the live Zhengfang markup shape and stripped of identity data. */
class ChangshaCollegeCourseImportTest {
    @Test
    fun fullWidthHeadingAndUnscheduledFooterDoNotBlockScheduledCourses() {
        // Real page shape: a 9-column title, then two ruler columns and seven weekdays.
        // No student name, ID, teacher name or session data is needed to reproduce it.
        val parsed = CourseTableParser.parseHtml("""
            <table id="kbgrid_table_0">
              <tr><td colspan="9"><div class="timetable_title">2026-2027学年第1学期 测试课表</div></td></tr>
              <tr><td>时间段</td><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td><td>星期六</td><td>星期日</td></tr>
              <tr><td rowspan="2">上午</td><td>1 08:00<br>08:45</td>
                <td id="1-1" rowspan="2" class="td_wrap"><div class="timetable_con">
                  <span class="title">测试课程甲</span><p>(1-2节)1-8周</p><p>教学楼A101</p>
                </div></td><td></td><td></td><td></td><td></td><td></td><td></td>
              </tr>
              <tr><td>2 08:55<br>09:40</td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
              <tr><td colspan="9">实践课程：课程设计(共1周)/15周;<br>其它课程：劳动教育(共2周)/12-13周/无;</td></tr>
            </table>
        """.trimIndent())

        assertTrue(parsed.errors.toString(), parsed.isSuccess)
        assertEquals(1, parsed.rows.size)
        assertEquals("测试课程甲", parsed.rows.single().name)
        assertEquals(1, parsed.rows.single().weekday)
        assertEquals(1, parsed.rows.single().startPeriod)
        assertEquals(2, parsed.rows.single().endPeriod)
        assertEquals(8 * 60 to 8 * 60 + 45, parsed.periodTimes[1])
        assertEquals(8 * 60 + 55 to 9 * 60 + 40, parsed.periodTimes[2])
    }

    @Test
    fun parsesChangshaPersonalCourseGridAndSelectedTerm() {
        val html = """
            <select id="xnxq"><option selected="selected">2026-2027</option></select>
            <select id="xq"><option selected="selected">第1学期</option></select>
            <div>选课 / 个人课表查询</div>
            <table id="kbgrid_table_0" class="table table-hover table-bordered text-center timetable1">
              <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td></tr>
              <tr>
                <td>上午</td><td id="1-1"></td>
                <td id="2-1" class="td_wrap" rowspan="2">
                  <div class="timetable_con text-left">
                    <span class="title">模拟电子技术</span>
                    <p><span title="节/周"></span>(1-2节)1-14周,16周</p>
                    <p><span title="上课地点"></span>理工楼A101</p>
                    <p><span title="教师"></span>教师甲</p>
                  </div>
                </td>
                <td id="3-1"></td><td id="4-1"></td><td id="5-1"></td>
              </tr>
              <tr>
                <td>上午</td><td id="1-3"></td>
                <td id="2-3" class="td_wrap">
                  <div class="timetable_con text-left">
                    <span class="title">复变函数与积分变换</span>
                    <p><span title="节/周"></span>(3-4节)1-8周</p>
                    <p><span title="上课地点"></span>理工楼C512</p>
                    <p><span title="教师"></span>教师乙</p>
                  </div>
                </td>
                <td id="3-3"></td><td id="4-3"></td><td id="5-3"></td>
              </tr>
              <tr>
                <td>下午</td><td id="1-5"></td>
                <td id="2-5" class="td_wrap">
                  <div class="timetable_con text-left">
                    <span class="title">单片机基础与应用</span>
                    <p><span title="节/周"></span>(5-6节)10-16周</p>
                    <p><span title="上课地点"></span>实验楼B204</p>
                    <p><span title="教师"></span>教师丙</p>
                  </div>
                </td>
                <td id="3-5"></td><td id="4-5"></td><td id="5-5"></td>
              </tr>
            </table>
        """.trimIndent()

        assertTrue(CourseImportPageDetector.inspect(html, "https://jwxt.ccsu.cn/jwglxt/kbcx/query").allowed)
        assertEquals("2026-2027-1", CourseImportTermDetector.detect(html, listOf("2026-2027", "第1学期")))

        val parsed = CourseTableParser.parseHtml(html)
        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        val schedules = CourseTableParser.toSchedules(
            accountId = "test-account",
            rows = parsed.rows,
            source = CourseSchedule.SOURCE_ACADEMIC_WEB,
            termOverride = "2026-2027-1"
        )
        assertEquals(3, schedules.size)
        assertEquals(setOf("模拟电子技术", "复变函数与积分变换", "单片机基础与应用"), schedules.map { it.name }.toSet())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16), schedules.first { it.name == "模拟电子技术" }.weeks)
        assertEquals("理工楼C512", schedules.first { it.name == "复变函数与积分变换" }.room)
    }

    @Test
    fun choosesPopulatedGridWhenPageKeepsAnEmptyLoadingGrid() {
        val html = """
            <table id="kbgrid_table_0" class="timetable1">
              <tr><td>节次</td><td>星期一</td><td>星期二</td></tr>
              <tr><td>上午</td><td></td><td></td></tr>
            </table>
            <table id="kbgrid_table_1" class="timetable1">
              <tr><td>节次</td><td>星期一</td><td>星期二</td></tr>
              <tr><td>上午</td><td></td><td id="2-1" class="td_wrap">
                <div class="timetable_con"><span class="title">模拟电子技术</span>
                  <p>(1-2节)1-14周</p><p>理工楼A101</p>
                </div>
              </td></tr>
            </table>
        """.trimIndent()

        val parsed = CourseTableParser.parseHtml(html)

        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        assertEquals(listOf("模拟电子技术"), parsed.rows.map { it.name })
    }
}
