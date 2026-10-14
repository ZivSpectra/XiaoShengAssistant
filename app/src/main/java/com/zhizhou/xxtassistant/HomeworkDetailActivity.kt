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
            ): Boolean = false
        }

        val loadPage = { webView.loadUrl(url) }
        if (accountId != null) {
            restoreAccountCookies(AccountManager(this).getCookie(accountId), loadPage)
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
            val cookie = listOf(
                "https://passport2.chaoxing.com",
                "https://i.chaoxing.com",
                "https://mooc1.chaoxing.com",
                "https://mooc1-api.chaoxing.com",
                "https://mooc2-ans.chaoxing.com"
            ).mapNotNull { manager.getCookie(it) }.joinToString("; ")
            if (cookie.isNotBlank()) AccountManager(this).updateCookie(accountId, cookie)
        }
        super.onDestroy()
        webView.destroy()
    }

    private fun restoreAccountCookies(cookie: String?, afterRestore: () -> Unit) {
        if (cookie.isNullOrBlank()) {
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
        val pairs = cookie.split(';').map { it.trim() }.filter { it.contains('=') }
        manager.removeAllCookies {
            domains.forEach { domain -> pairs.forEach { manager.setCookie(domain, it) } }
            manager.flush()
            runOnUiThread { afterRestore() }
        }
    }
}
