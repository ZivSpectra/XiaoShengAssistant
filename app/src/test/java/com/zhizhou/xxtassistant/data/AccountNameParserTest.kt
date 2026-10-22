package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountNameParserTest {
    @Test
    fun readsNameOnlyFromExplicitProfileObject() {
        assertEquals("测试同学", AccountNameParser.parse("""{"userInfo":{"uid":"42","realName":"测试同学"},"course":{"name":"高等数学"}}""", "42"))
    }

    @Test
    fun doesNotUseCourseNameOrMismatchedProfile() {
        assertNull(AccountNameParser.parse("""{"course":{"name":"高等数学"},"userInfo":{"uid":"43","name":"别的用户"}}""", "42"))
    }

    @Test
    fun readsProfileStructurallyFromLargeCourseResponse() {
        val channels = (1..58).joinToString(",") { index ->
            """{"content":{"course":{"data":[{"name":"课程$index"}]}}}"""
        }
        val body = """{"userInfo":{"uid":"42","realName":"测试同学"},"channelList":[$channels]}"""

        assertEquals("测试同学", AccountNameParser.parse(body, "42"))
    }
}
