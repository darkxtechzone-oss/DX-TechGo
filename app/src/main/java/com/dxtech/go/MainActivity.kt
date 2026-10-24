package com.dxtech.go

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Tab(val web: WebView) {
    var title = "New tab"
    var started = false
}

class MainActivity : Activity() {
    private val BLUE = Color.parseColor("#1A73E8")
    private val GRAY = Color.parseColor("#F1F3F4")
    private val TXT = Color.parseColor("#202124")
    private val SUB = Color.parseColor("#5F6368")
    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val tabs = mutableListOf<Tab>()
    private lateinit var cur: Tab
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var home: View
    private lateinit var urlText: TextView
    private lateinit var leftIcon: ImageView
    private lateinit var reloadBtn: ImageView
    private lateinit var tabBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var mobileUA: String
    private var desktop = false
    private var secure = true
    private var loading = false
    private var suggestToken = 0
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rr(color: Int, r: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun ripple(v: View) {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        v.setBackgroundResource(tv.resourceId)
    }

    private fun icon(res: Int, size: Int, pad: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        ripple(this)
    }

    private fun applySecure() {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        applySecure()
        mobileUA = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        buildUi()
        newTab(intent?.dataString)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { newTab(it) }
    }

    override fun onDestroy() {
        wipe()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustom()
            cur.started && cur.web.canGoBack() -> cur.web.goBack()
            tabs.size > 1 -> closeTab(cur)
            else -> super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 77) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    // ---------- UI ----------
    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        leftIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(14) }
        }
        urlText = TextView(this).apply {
            textSize = 15f
            setTextColor(TXT)
            setHintTextColor(SUB)
            hint = "Search or type URL"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(10); marginEnd = dp(4)
            }
        }
        reloadBtn = icon(R.drawable.ic_reload, 40, 8)
        reloadBtn.setOnClickListener {
            if (loading) cur.web.stopLoading() else if (cur.started) cur.web.reload()
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rr(GRAY, 22)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener { openSearch() }
            addView(leftIcon); addView(urlText); addView(reloadBtn)
        }
        tabBtn = TextView(this).apply {
            text = "1"
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TXT)
            background = rr(Color.TRANSPARENT, 6, TXT, 2)
        }
        val tabWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            addView(tabBtn, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { showTabs() }
            ripple(this)
        }
        val menuBtn = icon(R.drawable.ic_more, 44, 10)
        menuBtn.setOnClickListener { showMenu(menuBtn) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(2), dp(6))
            addView(pill); addView(tabWrap); addView(menuBtn)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = ColorStateList.valueOf(BLUE)
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
        }
        content = FrameLayout(this)
        home = buildHome()

        root.addView(bar)
        root.addView(progress)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun buildHome(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(110))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(26).toFloat())
                }
            }
        }
        val name = TextView(this).apply {
            text = "DxTech GO"
            textSize = 26f
            setTextColor(TXT)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        val sub = TextView(this).apply {
            text = "Private browsing"
            textSize = 14f
            setTextColor(SUB)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(28))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rr(Color.WHITE, 26, Color.parseColor("#DADCE0"), 1)
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply {
                marginStart = dp(4); marginEnd = dp(4)
            }
            setOnClickListener { openSearch() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_search)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(18) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Search or type URL"
                textSize = 16f
                setTextColor(SUB)
                setPadding(dp(14), 0, 0, 0)
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, 0)
        }
        val sites = listOf(
            Triple("Google", "G", "https://www.google.com"),
            Triple("DuckDuckGo", "D", "https://duckduckgo.com"),
            Triple("YouTube", "Y", "https://m.youtube.com"),
            Triple("Wikipedia", "W", "https://www.wikipedia.org")
        )
        for ((label, letter, url) in sites) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(80), -2)
                setOnClickListener { load(url) }
            }
            item.addView(TextView(this).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = 20f
                setTextColor(BLUE)
                setTypeface(typeface, Typeface.BOLD)
                background = rr(GRAY, 26)
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            })
            item.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(SUB)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            })
            row.addView(item)
        }
        col.addView(logo); col.addView(name); col.addView(sub); col.addView(box); col.addView(row)
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.WHITE)
            addView(col)
        }
    }

    // ---------- tabs ----------
    private fun newTab(url: String?) {
        val w = WebView(this)
        val t = Tab(w)
        configure(w, t)
        tabs.add(t)
        cur = t
        loading = false
        progress.visibility = View.INVISIBLE
        if (url != null) load(url) else showCur()
    }

    private fun switchTo(t: Tab) {
        cur = t
        loading = false
        progress.visibility = View.INVISIBLE
        showCur()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        tabs.remove(t)
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) newTab(null)
        else if (t === cur) switchTo(tabs[minOf(i, tabs.size - 1)])
        else updateBar()
    }

    private fun showCur() {
        content.removeAllViews()
        content.addView(if (cur.started) cur.web else home, FrameLayout.LayoutParams(-1, -1))
        updateBar()
    }

    private fun updateBar() {
        tabBtn.text = tabs.size.toString()
        val u = if (cur.started) cur.web.url else null
        if (u != null) {
            urlText.text = Uri.parse(u).host?.removePrefix("www.") ?: u
            leftIcon.setImageResource(if (u.startsWith("https")) R.drawable.ic_lock else R.drawable.ic_search)
        } else {
            urlText.text = ""
            leftIcon.setImageResource(R.drawable.ic_search)
        }
        reloadBtn.visibility = if (cur.started) View.VISIBLE else View.GONE
        reloadBtn.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_reload)
    }

    private fun showTabs() {
        val dlg = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        val title = TextView(this).apply {
            text = "Tabs"
            textSize = 22f
            setTextColor(TXT)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val add = TextView(this).apply {
            text = "+ New tab"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = rr(BLUE, 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { dlg.dismiss(); newTab(null) }
        }
        head.addView(title); head.addView(add)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        fun render() {
            list.removeAllViews()
            for (t in tabs.toList()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(4), dp(4), dp(4))
                    background = rr(if (t === cur) Color.parseColor("#E8F0FE") else GRAY, 14)
                    layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) }
                    setOnClickListener { dlg.dismiss(); switchTo(t) }
                }
                row.addView(TextView(this).apply {
                    text = if (t.started) t.title else "New tab"
                    textSize = 15f
                    setTextColor(TXT)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                val x = icon(R.drawable.ic_close, 44, 11)
                x.setOnClickListener {
                    closeTab(t)
                    render()
                }
                row.addView(x)
                list.addView(row)
            }
        }
        render()
        col.addView(head)
        col.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(col)
        dlg.window?.apply {
            setLayout(-1, -1)
            statusBarColor = Color.WHITE
            if (secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dlg.show()
    }

    // ---------- search overlay ----------
    private fun openSearch() {
        val dlg = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val back = icon(R.drawable.ic_back, 44, 10)
        back.setOnClickListener { dlg.dismiss() }
        val sIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(14) }
        }
        val et = EditText(this).apply {
            background = null
            hint = "Search or type URL"
            textSize = 16f
            setTextColor(TXT)
            setHintTextColor(SUB)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setPadding(dp(10), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val startText = if (cur.started) (cur.web.url ?: "") else ""
        et.setText(startText)
        val clear = icon(R.drawable.ic_close, 40, 9)
        clear.visibility = if (startText.isEmpty()) View.GONE else View.VISIBLE
        clear.setOnClickListener { et.setText("") }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rr(GRAY, 22)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            addView(sIcon); addView(et); addView(clear)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(10), dp(6))
            addView(back); addView(field)
        }
        val ad = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, mutableListOf<String>()) {
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val tv = super.getView(p, v, parent) as TextView
                tv.setTextColor(TXT)
                tv.textSize = 16f
                tv.setPadding(dp(18), dp(12), dp(18), dp(12))
                val d = this@MainActivity.getDrawable(R.drawable.ic_search)!!
                d.setBounds(0, 0, dp(18), dp(18))
                tv.setCompoundDrawables(d, null, null, null)
                tv.compoundDrawablePadding = dp(16)
                return tv
            }
        }
        val lv = ListView(this).apply {
            divider = null
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, pos, _ ->
            val s = ad.getItem(pos)
            dlg.dismiss()
            if (s != null) go(s)
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                suggest(q) { res -> ad.clear(); ad.addAll(res) }
            }
        })
        et.setOnEditorActionListener { _, _, _ ->
            val s = et.text.toString()
            dlg.dismiss()
            go(s)
            true
        }
        col.addView(top)
        col.addView(lv)
        dlg.setContentView(col)
        dlg.window?.apply {
            setLayout(-1, -1)
            statusBarColor = Color.WHITE
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dlg.show()
        et.post { et.requestFocus(); et.selectAll() }
    }

    private fun suggest(q: String, cb: (List<String>) -> Unit) {
        val t = ++suggestToken
        if (q.isEmpty()) { cb(emptyList()); return }
        cb(listOf(q))
        if (q.contains("://")) return
        Thread {
            try {
                val c = URL("https://duckduckgo.com/ac/?type=list&q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val arr = JSONArray(c.inputStream.bufferedReader().readText()).getJSONArray(1)
                val out = mutableListOf(q)
                for (i in 0 until minOf(arr.length(), 7)) {
                    val s = arr.getString(i)
                    if (s != q) out.add(s)
                }
                runOnUiThread { if (t == suggestToken) cb(out) }
            } catch (e: Exception) {
            }
        }.start()
    }

    // ---------- navigation ----------
    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
        }
        load(url)
    }

    private fun load(url: String) {
        cur.started = true
        showCur()
        cur.web.loadUrl(url)
    }

    private fun applyUA(w: WebView) {
        w.settings.userAgentString = if (desktop) DESKTOP_UA else mobileUA
        w.settings.loadWithOverviewMode = desktop
    }

    private fun errorHtml(msg: String): String =
        "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='font-family:sans-serif;padding:32px;color:#202124'>" +
            "<h2>This site can\u2019t be reached</h2><p style='color:#5F6368'>" +
            msg.replace("<", "&lt;") +
            "</p><p style='color:#5F6368'>Check your connection, or set a proxy from the menu (\u22EE).</p></body></html>"

    private fun configure(w: WebView, t: Tab) {
        w.setBackgroundColor(Color.WHITE)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
        }
        applyUA(w)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                return when {
                    u.startsWith("http://") -> { v.loadUrl("https://" + u.removePrefix("http://")); true }
                    u.startsWith("https://") || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:") -> false
                    else -> {
                        try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (e: Exception) {}
                        true
                    }
                }
            }

            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                if (t === cur) {
                    loading = true
                    progress.progress = 5
                    progress.visibility = View.VISIBLE
                    updateBar()
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                if (t === cur) {
                    loading = false
                    progress.visibility = View.INVISIBLE
                    updateBar()
                }
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) {
                    v.loadDataWithBaseURL(null, errorHtml(e.description.toString()), "text/html", "utf-8", r.url.toString())
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (t === cur) {
                    progress.progress = p
                    progress.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE
                }
            }

            override fun onReceivedTitle(v: WebView, title: String) {
                t.title = title
            }

            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                customView = view
                customCb = cb
                setContentView(view)
            }

            override fun onHideCustomView() {
                hideCustom()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams?.createIntent(), 77)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }

        w.setDownloadListener { url, ua, cd, mime, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mime)
                req.addRequestHeader("User-Agent", ua)
                req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime))
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }

        w.setOnLongClickListener {
            val r = w.hitTestResult
            val link = r.extra
            if ((r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && link != null) {
                AlertDialog.Builder(this).setItems(arrayOf("Open in new tab", "Copy link")) { _, i ->
                    if (i == 0) newTab(link)
                    else (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("url", link))
                }.show()
                true
            } else false
        }
    }

    private fun hideCustom() {
        if (customView == null) return
        setContentView(root)
        customCb?.onCustomViewHidden()
        customView = null
        customCb = null
    }

    // ---------- menu ----------
    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "New tab")
        m.add(0, 2, 0, "Forward")
        m.add(0, 3, 0, "Reload")
        m.add(0, 4, 0, "Share")
        m.add(0, 5, 0, "Desktop site").apply { isCheckable = true; isChecked = desktop }
        m.add(0, 6, 0, "Block screenshots").apply { isCheckable = true; isChecked = secure }
        m.add(0, 7, 0, "Set proxy")
        m.add(0, 8, 0, "Clear proxy")
        m.add(0, 9, 0, "Clear browsing data")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(null)
                2 -> if (cur.started && cur.web.canGoForward()) cur.web.goForward()
                3 -> if (cur.started) cur.web.reload()
                4 -> {
                    val u = if (cur.started) cur.web.url else null
                    if (u != null) {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, u)
                        }
                        startActivity(Intent.createChooser(i, "Share"))
                    }
                }
                5 -> {
                    desktop = !desktop
                    tabs.forEach { applyUA(it.web) }
                    if (cur.started) cur.web.reload()
                }
                6 -> { secure = !secure; applySecure() }
                7 -> askProxy()
                8 -> setProxy(null)
                9 -> { wipe(); Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show() }
            }
            true
        }
        pm.show()
    }

    private fun askProxy() {
        val input = EditText(this).apply {
            hint = "socks5://host:port  or  host:port"
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        AlertDialog.Builder(this).setTitle("Proxy").setView(input)
            .setPositiveButton("OK") { _, _ -> setProxy(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setProxy(rule: String?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Toast.makeText(this, "Proxy not supported on this WebView", Toast.LENGTH_LONG).show()
            return
        }
        val pc = ProxyController.getInstance()
        if (rule.isNullOrEmpty()) {
            pc.clearProxyOverride({ it.run() }, { Toast.makeText(this, "Proxy off", Toast.LENGTH_SHORT).show() })
        } else {
            pc.setProxyOverride(ProxyConfig.Builder().addProxyRule(rule).build(), { it.run() }, {
                Toast.makeText(this, "Proxy on: $rule", Toast.LENGTH_SHORT).show()
                if (cur.started) cur.web.reload()
            })
        }
    }

    private fun wipe() {
        tabs.forEach {
            it.web.clearHistory()
            it.web.clearCache(true)
            it.web.clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}
