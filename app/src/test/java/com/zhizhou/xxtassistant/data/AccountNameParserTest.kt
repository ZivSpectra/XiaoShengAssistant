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

    @Test
    fun readsOnlyExplicitVisibleAccountLabel() {
        assertEquals("测试同学", AccountNameParser.parseVisibleProfile("姓名：测试同学\n我的课程"))
        assertNull(AccountNameParser.parseVisibleProfile("我的课程\n高等数学\n课程：复变函数"))
    }

    @Test
    fun authenticatedPageFallsBackToVisibleProfile() {
        assertEquals("测试同学", AccountNameParser.parseAuthenticatedPage("欢迎回来\n姓名：测试同学\n我的课程", "42"))
        assertNull(AccountNameParser.parseAuthenticatedPage("我的课程\n高等数学", "42"))
    }

    @Test
    fun readsRootLevelRealNameWhenCourseApiDoesNotNestProfile() {
        assertEquals(
            "周智",
            AccountNameParser.parse("""{"uid":"42","realname":"周智","channelList":[]}""", "42")
        )
    }

    @Test
    fun readsLabeledNameFromHtmlMarkup() {
        assertEquals(
            "周智",
            AccountNameParser.parseAuthenticatedPage("<span>真实姓名：</span><b>周智</b>", "42")
        )
    }
}
