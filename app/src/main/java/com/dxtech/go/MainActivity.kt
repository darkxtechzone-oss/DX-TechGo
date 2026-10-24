package com.dxtech.go

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bar: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Private: blocks screenshots and recent-apps preview
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "<"; setOnClickListener { if (web.canGoBack()) web.goBack() } }
        bar = EditText(this).apply {
            hint = "Search or type URL"; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val goBtn = Button(this).apply { text = "Go"; setOnClickListener { go(bar.text.toString()) } }
        val menu = Button(this).apply { text = "..."; setOnClickListener { showMenu() } }
        top.addView(back)
        top.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(goBtn); top.addView(menu)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            saveFormData = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (u.startsWith("http://")) { v.loadUrl("https://" + u.removePrefix("http://")); return true }
                return false
            }
            override fun onPageFinished(v: WebView, url: String) { bar.setText(url) }
        }
        web.webChromeClient = WebChromeClient()

        root.addView(top)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        go(intent?.dataString ?: "https://duckduckgo.com")
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
            else -> "https://$t"
        }
        web.loadUrl(url)
    }

    private fun showMenu() {
        val items = arrayOf("Set proxy", "Clear proxy", "Clear all data now")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> askProxy()
                1 -> setProxy(null)
                2 -> wipe()
            }
        }.show()
    }

    private fun askProxy() {
        val input = EditText(this).apply { hint = "socks5://host:port  or  host:port" }
        AlertDialog.Builder(this).setTitle("Proxy").setView(input)
            .setPositiveButton("OK") { _, _ -> setProxy(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setProxy(rule: String?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Toast.makeText(this, "Proxy not supported on this WebView", Toast.LENGTH_LONG).show(); return
        }
        val pc = ProxyController.getInstance()
        if (rule.isNullOrEmpty()) {
            pc.clearProxyOverride({ it.run() }, { Toast.makeText(this, "Proxy off", Toast.LENGTH_SHORT).show() })
        } else {
            pc.setProxyOverride(ProxyConfig.Builder().addProxyRule(rule).build(), { it.run() },
                { Toast.makeText(this, "Proxy on: $rule", Toast.LENGTH_SHORT).show(); web.reload() })
        }
    }

    private fun wipe() {
        web.clearHistory(); web.clearCache(true); web.clearFormData()
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true }
        return super.onKeyDown(k, e)
    }

    override fun onDestroy() { wipe(); super.onDestroy() }
}
