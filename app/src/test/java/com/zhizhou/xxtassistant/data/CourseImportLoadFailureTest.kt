package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseImportLoadFailureTest {
    @Test
    fun distinguishesDnsConnectionAndTimeoutFailures() {
        assertEquals(CourseImportLoadFailure.Kind.DNS, CourseImportLoadFailure.from(-2).kind)
        assertEquals(CourseImportLoadFailure.Kind.CONNECTION, CourseImportLoadFailure.from(-6).kind)
        assertEquals(CourseImportLoadFailure.Kind.TIMEOUT, CourseImportLoadFailure.from(-8).kind)
    }

    @Test
    fun unknownFailureDoesNotPretendTheNetworkIsAlwaysTheCause() {
        val failure = CourseImportLoadFailure.from(-1)

        assertEquals(CourseImportLoadFailure.Kind.OTHER, failure.kind)
        assertTrue(failure.userMessage.contains("教务页面加载失败"))
        assertFalse(failure.userMessage.contains("检查网址和网络"))
    }
}
