package com.zhizhou.xxtassistant.data

/** A WebView main-frame failure translated into a specific, user-readable cause. */
data class CourseImportLoadFailure(
    val code: Int,
    val kind: Kind
) {
    enum class Kind { DNS, CONNECTION, TIMEOUT, SSL, BAD_URL, REDIRECT, OTHER }

    val userMessage: String
        get() = when (kind) {
            Kind.DNS -> "教务页面域名无法解析（WebView 错误码 $code）。请确认学校网址及当前网络/VPN。"
            Kind.CONNECTION -> "无法连接教务网站（WebView 错误码 $code）。请稍后重试或确认学校网络可访问。"
            Kind.TIMEOUT -> "教务页面加载超时（WebView 错误码 $code）。请等待后重试。"
            Kind.SSL -> "教务网站的安全连接建立失败（WebView 错误码 $code）。请使用学校提供的官方 HTTPS 网址。"
            Kind.BAD_URL -> "教务网址格式无效（WebView 错误码 $code）。请检查网址。"
            Kind.REDIRECT -> "教务网站跳转次数过多（WebView 错误码 $code）。请重新打开学校官网并登录。"
            Kind.OTHER -> "教务页面加载失败（WebView 错误码 $code）。请复制诊断日志以便定位具体阶段。"
        }

    companion object {
        // Values are WebViewClient.ERROR_* constants, kept primitive for JVM tests.
        fun from(code: Int): CourseImportLoadFailure = CourseImportLoadFailure(
            code = code,
            kind = when (code) {
                -2 -> Kind.DNS
                -6, -7 -> Kind.CONNECTION
                -8 -> Kind.TIMEOUT
                -11 -> Kind.SSL
                -12 -> Kind.BAD_URL
                -9 -> Kind.REDIRECT
                else -> Kind.OTHER
            }
        )
    }
}
