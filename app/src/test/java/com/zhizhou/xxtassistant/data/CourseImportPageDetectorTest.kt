package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseImportPageDetectorTest {
    @Test
    fun rejectsLoginAndMenuPagesEvenWhenTheyContainAnUnrelatedTable() {
        val html = """
            <h1>教务系统登录</h1>
            <table><tr><td>选课</td><td>首页</td></tr></table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://example.edu/login")

        assertFalse(decision.reason, decision.allowed)
    }

    @Test
    fun acceptsACompletePersonalCourseGrid() {
        val html = """
            <div>选课 → 个人课表查询</div>
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td>1-2节</td><td class="kbcontent">模拟电子技术<br>(1-16周)</td><td></td></tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://example.edu/course/query")

        assertTrue(decision.reason, decision.allowed)
    }

    @Test
    fun acceptsCourseGridWithoutSchoolSpecificPersonalContext() {
        val html = """
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th></tr>
              <tr><td>1-2节</td><td class="kbcontent">模拟电子技术</td></tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://example.edu/portal")

        assertTrue(decision.reason, decision.allowed)
    }

    @Test
    fun doesNotUseTheUrlPathToClassifyAWeekGrid() {
        val html = """
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th></tr>
              <tr><td>1-2节</td><td class="kbcontent">模拟电子技术</td></tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://example.edu/teacher/timetable")

        assertTrue(decision.reason, decision.allowed)
    }

    @Test
    fun acceptsPageWithExplicitPersonalTitleWithoutBreadcrumb() {
        val html = """
            <title>个人课表查询</title>
            <table id="kbgrid_table_0">
              <tr><th>时间段</th><th>周一</th></tr>
              <tr><td>1-2节</td><td class="timetable_con">复变函数</td></tr>
            </table>
        """.trimIndent()

        assertTrue(CourseImportPageDetector.inspect(html).allowed)
    }

    @Test
    fun acceptsNonZeroZhengfangGridIdWithoutSpecificQueryPath() {
        val html = """
            <table id="kbgrid_table_1">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td>1-2节</td><td class="kbcontent">高等数学</td><td></td></tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(
            html,
            "https://example.edu/timetable"
        )

        assertTrue(decision.reason, decision.allowed)
    }

    @Test
    fun acceptsSanitisedZhengfangStyleGridWithoutSchoolMarkers() {
        // Mirrors the observed Zhengfang timetable structure. All course data is synthetic.
        val html = """
            <table id="kbgrid_table_0">
              <tr>
                <th>时间段</th><th>节次</th><th>星期一</th><th>星期二</th>
                <th>星期三</th><th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
              </tr>
              <tr>
                <td>上午</td><td>1 08:00<br/>08:45</td>
                <td id="1-1"></td>
                <td id="2-1" class="td_wrap" rowspan="2">
                  <div class="timetable_con text-left">
                    <span class="title">课程甲</span>
                    <p>(1-2节)1-8周</p>
                    <p>教学楼A101</p>
                    <p>教师甲</p>
                  </div>
                </td>
                <td id="3-1"></td><td id="4-1"></td><td id="5-1"></td>
                <td id="6-1"></td><td id="7-1"></td>
              </tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://example.edu/student/timetable")

        assertTrue(decision.reason, decision.allowed)
    }

    @Test
    fun rejectsOrdinaryPortalTableWithoutTimetableShape() {
        val html = """
            <h1>学生门户</h1>
            <table>
              <tr><th>功能</th><th>状态</th></tr>
              <tr><td>选课</td><td>已开放</td></tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://example.edu/portal")

        assertFalse(decision.reason, decision.allowed)
    }

    @Test
    fun marksEmptyAcademicShellAsRetryableButNotLoginPage() {
        val html = """
            <div id="kbgrid_loading">正在加载课表</div>
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td>1-2节</td><td></td><td></td></tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://jwxt.example/timetable")

        assertFalse(decision.reason, decision.allowed)
        assertTrue("动态课表空壳应允许稍后重试", decision.retryable)
    }

    @Test
    fun neverRetriesAVisibleLoginPage() {
        val html = """
            <h1>统一身份认证登录</h1>
            <form><input name="username"/><input name="password"/></form>
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th></tr>
              <tr><td>1-2节</td><td></td></tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://cas.example/login")

        assertFalse(decision.reason, decision.allowed)
        assertFalse("登录页应提示用户登录，而不是重复读取", decision.retryable)
    }

    @Test
    fun doesNotTreatAuthenticatedExitLinkAsLoginPage() {
        val html = """
            <header>当前用户　<a href="/logout">退出登录</a></header>
            <table id="kbgrid_table_0">
              <tr><th>节次</th><th>星期一</th></tr>
              <tr><td>1-2节</td><td></td></tr>
            </table>
        """.trimIndent()

        val decision = CourseImportPageDetector.inspect(html, "https://jwxt.example/timetable")

        assertFalse(decision.reason, decision.allowed)
        assertTrue("已登录页面的动态空壳仍应等待渲染", decision.retryable)
    }
}
