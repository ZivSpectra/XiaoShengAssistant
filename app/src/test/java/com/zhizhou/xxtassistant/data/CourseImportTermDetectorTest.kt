package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseImportTermDetectorTest {
    @Test
    fun prefersLiveSelectedOptionOverHistoricalOptionsInOuterHtml() {
        val html = """
            <select id="xnxq">
              <option value="2025-2026-2">2025-2026学年第二学期</option>
              <option value="2026-2027-1">2026-2027学年第一学期</option>
            </select>
            <table id="kbgrid_table_0"><tr><th>星期一</th><th>节次</th></tr></table>
        """.trimIndent()

        assertEquals(
            "2026-2027-1",
            CourseImportTermDetector.detect(html, listOf("2026-2027学年第一学期"))
        )
    }

    @Test
    fun combinesIndependentAcademicYearAndSemesterSelections() {
        val html = """
            <select id="xnm"><option>2025-2026</option><option>2026-2027</option></select>
            <select id="xqm"><option>2</option><option>1</option></select>
            <table id="kbgrid_table_0"><tr><th>星期一</th><th>节次</th></tr></table>
        """.trimIndent()

        assertEquals(
            "2026-2027-1",
            CourseImportTermDetector.detect(html, listOf("2026-2027", "1"))
        )
    }

    @Test
    fun supportsSpringAndAutumnLabels() {
        assertEquals(
            "2026-2027-2",
            CourseImportTermDetector.detect("<div>2026-2027学年春季学期</div>")
        )
        assertEquals(
            "2026-2027-1",
            CourseImportTermDetector.detect("<div>2026-2027学年秋季学期</div>")
        )
    }

    @Test
    fun refusesToGuessWhenPageContainsOnlyHistoricalOptions() {
        val html = """
            <select>
              <option>2025-2026学年第二学期</option>
              <option>2026-2027学年第一学期</option>
            </select>
        """.trimIndent()

        assertNull(CourseImportTermDetector.detect(html))
    }

    @Test
    fun refusesConflictingLiveSelections() {
        assertNull(
            CourseImportTermDetector.detect(
                "<div>2026-2027学年第一学期</div>",
                listOf("2025-2026学年第二学期", "2026-2027学年第一学期")
            )
        )
    }
}
