package com.zhizhou.xxtassistant

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.util.DebugLog
import com.zhizhou.xxtassistant.util.WebViewCookieTarget

/**
 * 作业详情页：用 WebView 打开学习通里对应的作业页面。
 *
 * 【重要 - 安全底线】
 * 这里只做一件事：把作业页面原样打开给你，由你亲手完成作业。
 * 本项目没有任何"自动提交作业 / 自动答题"的代码，请放心使用。
 *
 * 登录会话说明：登录时 Cookie 已保存在系统 WebView 的 Cookie 管理器里，
 * 这里打开学习通页面时会自动带上，不需要重新登录。
 */
@SuppressLint("SetJavaScriptEnabled")
class HomeworkDetailActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_homework_detail)

        val url = intent.getStringExtra("url") ?: run {
            finish()
            return
        }
        val title = intent.getStringExtra("title") ?: "作业"
        val accountId = intent.getStringExtra("accountId")

        setTitle(title)
        webView = findViewById(R.id.webview_homework)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request?.isForMainFrame != true) return false
                val target = request.url?.toString().orEmpty()
                if (WebViewCookieTarget.isSecureLearningPlatformUrl(target)) return false
                DebugLog.log(this@HomeworkDetailActivity, "已拦截作业详情页外部跳转")
                return true
            }
        }

        val loadPage = { webView.loadUrl(url) }
        if (accountId != null) {
            val accounts = AccountManager(this)
            restoreAccountCookies(
                accounts.getCookie(accountId),
                accounts.getCookiesByDomain(accountId),
                loadPage
            )
        } else {
            loadPage()
        }

        // 返回键：先回退网页历史，网页到头了才退出页面（新版返回键 API）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        val accountId = intent.getStringExtra("accountId")
        if (!accountId.isNullOrBlank()) {
            val manager = CookieManager.getInstance()
            val domains = listOf(
                "https://passport2.chaoxing.com",
                "https://i.chaoxing.com",
                "https://mooc1.chaoxing.com",
                "https://mooc1-api.chaoxing.com",
                "https://mooc2-ans.chaoxing.com"
            ).mapNotNull { domain -> manager.getCookie(domain)?.takeIf { it.isNotBlank() }?.let { domain to it } }
                .toMap()
            if (domains.isNotEmpty()) AccountManager(this).updateCookiesByDomain(accountId, domains)
        }
        super.onDestroy()
        webView.destroy()
    }

    private fun restoreAccountCookies(
        cookie: String?,
        cookiesByDomain: Map<String, String>,
        afterRestore: () -> Unit
    ) {
        if (cookie.isNullOrBlank() && cookiesByDomain.isEmpty()) {
            afterRestore()
            return
        }
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val domains = listOf(
            "https://passport2.chaoxing.com",
            "https://i.chaoxing.com",
            "https://mooc1.chaoxing.com",
            "https://mooc1-api.chaoxing.com",
            "https://mooc2-ans.chaoxing.com"
        )
        // Keep the process-wide WebView jar intact and restore each cookie to
        // its original host. Flattening every route/JSESSIONID onto every
        // domain is what previously caused intermittent 400 responses.
        val source = if (cookiesByDomain.isNotEmpty()) cookiesByDomain else
            domains.associateWith { cookie.orEmpty() }
        val writes = source.flatMap { (domain, raw) ->
            // AccountManager stores normalized host names (without a scheme),
            // while WebView requires an absolute URL when setting a cookie.
            // Keep compatibility with older entries that already contain one.
            val target = WebViewCookieTarget.fromDomain(domain)
            raw.split(';').map { it.trim() }.filter { it.contains('=') }.map { target to it }
        }
        if (writes.isEmpty()) {
            afterRestore()
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(writes.size)
        writes.forEach { (domain, pair) ->
            manager.setCookie(domain, pair) {
                if (remaining.decrementAndGet() == 0) {
                    manager.flush()
                    runOnUiThread(afterRestore)
                }
            }
        }
    }

}
