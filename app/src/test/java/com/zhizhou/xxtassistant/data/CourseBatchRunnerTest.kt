package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseBatchRunnerTest {

    @Test
    fun oneBlockedCourseDoesNotDiscardSuccessfulCourses() {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val result = CourseBatchRunner.run(
            items = listOf("模拟电子技术", "被风控课程", "单片机基础与应用"),
            parallelism = 2,
            fetch = { course ->
                if (course == "被风控课程") {
                    throw XueXiTongApi.AntiSpiderException("verification required")
                }
                listOf(course)
            },
            onSuccess = { course, _ -> successes += course },
            onFailure = { course, _ -> failures += course }
        )

        assertEquals(
            listOf(listOf("模拟电子技术"), listOf("单片机基础与应用")),
            result
        )
        assertEquals(2, successes.size)
        assertEquals(listOf("被风控课程"), failures)
        assertTrue("a single course failure must not abort the batch", result.isNotEmpty())
    }
}
