package com.zhizhou.xxtassistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DebugLogTest {

    @Test
    fun sanitizeRedactsSessionFieldsAndQueryValues() {
        val message = "GET https://example.test/work?uid=123&enc=secret HTTP 200 " +
            "cookie=session-value account=student password=plain-text"

        val sanitized = DebugLog.sanitizeForTest(message)

        assertTrue(sanitized.contains("https://example.test/work"))
        assertFalse(sanitized.contains("uid=123"))
        assertFalse(sanitized.contains("enc=secret"))
        assertFalse(sanitized.contains("session-value"))
        assertFalse(sanitized.contains("student"))
        assertFalse(sanitized.contains("plain-text"))
        assertTrue(sanitized.contains("<redacted>"))
    }

    @Test
    fun sanitizeAlsoRedactsCourseAndHomeworkIdentifiersOutsideUrls() {
        val sanitized = DebugLog.sanitizeForTest(
            "courseId=123 classId=456 workId=789 taskrefId=secret userId=student"
        )
        assertFalse(sanitized.contains("123"))
        assertFalse(sanitized.contains("456"))
        assertFalse(sanitized.contains("789"))
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("student"))
        assertEquals(5, Regex("<redacted>").findAll(sanitized).count())
    }

    @Test
    fun safeUrlDropsQueryAndFragment() {
        assertTrue(
            DebugLog.safeUrl("https://example.test/path?courseId=1#fragment") ==
                "https://example.test/path"
        )
    }

    @Test
    fun copiedLegacyLogsAreSanitizedAgain() {
        val copied = DebugLog.sanitizeForCopyForTest(
            "GET https://example.test/work?uid=123&enc=secret HTTP 200 cookie=session-value"
        )

        assertTrue(copied.contains("https://example.test/work"))
        assertFalse(copied.contains("uid=123"))
        assertFalse(copied.contains("enc=secret"))
        assertFalse(copied.contains("session-value"))
    }

    @Test
    fun logLineIsCapped() {
        assertEquals(2048, DebugLog.boundedLineForTest("x".repeat(5000)).length)
    }

    @Test
    fun logRotatesAtConfiguredLimitAndKeepsOnePreviousSegment() {
        val directory = Files.createTempDirectory("debug-log-test").toFile()
        val current = File(directory, "debug_log.txt")
        val previous = File(directory, "debug_log.previous.txt")
        try {
            DebugLog.appendLineForTest(current, "12345678", 10)
            DebugLog.appendLineForTest(current, "abc", 10)

            assertTrue(current.length() <= 10L)
            assertEquals("12345678", previous.readText())
            assertEquals("abc", current.readText())
        } finally {
            current.delete()
            previous.delete()
            directory.delete()
        }
    }

    @Test
    fun oversizedLegacyLogIsDiscardedBeforeWritingBoundedData() {
        val directory = Files.createTempDirectory("debug-log-test").toFile()
        val current = File(directory, "debug_log.txt")
        val previous = File(directory, "debug_log.previous.txt")
        try {
            current.writeText("x".repeat(100))
            DebugLog.appendLineForTest(current, "ok", 10)

            assertEquals("ok", current.readText())
            assertFalse(previous.exists())
        } finally {
            current.delete()
            previous.delete()
            directory.delete()
        }
    }

    @Test
    fun readTailReturnsOnlyTheRequestedRecentBytes() {
        val directory = Files.createTempDirectory("debug-log-test").toFile()
        val current = File(directory, "debug_log.txt")
        try {
            current.writeText("0123456789abcdef")
            assertEquals("89abcdef", DebugLog.readTailForTest(current, 8))
        } finally {
            current.delete()
            directory.delete()
        }
    }
}
