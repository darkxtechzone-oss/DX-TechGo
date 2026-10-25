package com.dxtech.go

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.res.Configuration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
    private var dark = false
    private var BLUE = Color.parseColor("#1A73E8")
    private var BG = Color.WHITE
    private var GRAY = Color.parseColor("#F1F3F4")
    private var TXT = Color.parseColor("#202124")
    private var SUB = Color.parseColor("#5F6368")
    private var SEL = Color.parseColor("#E8F0FE")
    private var STROKE = Color.parseColor("#DADCE0")
    private var ONBLUE = Color.WHITE
    private lateinit var srl: SwipeRefreshLayout
    private var pendingUrl: String? = null
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
    private var blockAds = true
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
        setColorFilter(SUB)
    }

    // ---------- theme ----------
    private fun applyTheme() {
        dark = when (prefs.getInt("theme", 0)) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        if (dark) {
            BLUE = Color.parseColor("#8AB4F8"); BG = Color.parseColor("#17181C"); GRAY = Color.parseColor("#2A2C32")
            TXT = Color.parseColor("#E8EAED"); SUB = Color.parseColor("#9AA0A6"); SEL = Color.parseColor("#2D3B55")
            STROKE = Color.parseColor("#3C4043"); ONBLUE = Color.parseColor("#0B1B3A")
        } else {
            BLUE = Color.parseColor("#1A73E8"); BG = Color.WHITE; GRAY = Color.parseColor("#F1F3F4")
            TXT = Color.parseColor("#202124"); SUB = Color.parseColor("#5F6368"); SEL = Color.parseColor("#E8F0FE")
            STROKE = Color.parseColor("#DADCE0"); ONBLUE = Color.WHITE
        }
    }

    private fun applyBars() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    @Suppress("DEPRECATION")
    private fun applyWebTheme(w: WebView) {
        w.setBackgroundColor(BG)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, dark)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK))
                WebSettingsCompat.setForceDark(w.settings, if (dark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        } catch (e: Exception) {
        }
    }

    private fun restyle() {
        applyTheme()
        applyBars()
        buildUi()
        tabs.forEach { applyWebTheme(it.web) }
        showCur()
    }

    private fun dlgTheme() = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    private fun fullTheme() = if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar
    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    private fun makeSrl(): SwipeRefreshLayout = SwipeRefreshLayout(this).apply {
        setColorSchemeColors(BLUE)
        setProgressBackgroundColorSchemeColor(GRAY)
        setOnRefreshListener {
            if (cur.started) {
                cur.web.reload()
            } else {
                isRefreshing = false
            }
            Unit
        }
        setOnChildScrollUpCallback { _, _ -> cur.web.scrollY > 0 }
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        applyTheme()
        applyBars()
        mobileUA = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        buildUi()
        newTab(intent?.dataString)
        applySavedProxy()
        checkUpdate(false)
    }

    override fun onResume() {
        super.onResume()
        val u = pendingUrl
        if (u != null && Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) {
            pendingUrl = null
            downloadApk(u)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val was = dark
        applyTheme()
        if (was != dark) restyle()
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
            setBackgroundColor(BG)
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
            background = rr(GRAY, 26, STROKE, 1)
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
            setBackgroundColor(BG)
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
        if (cur.started) {
            (cur.web.parent as? ViewGroup)?.removeView(cur.web)
            srl = makeSrl()
            srl.addView(cur.web, ViewGroup.LayoutParams(-1, -1))
            content.addView(srl, FrameLayout.LayoutParams(-1, -1))
        } else {
            content.addView(home, FrameLayout.LayoutParams(-1, -1))
        }
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(170).start()
        updateBar()
    }

    private fun updateBar() {
        val cnt = tabs.size.toString()
        if (tabBtn.text.toString() != cnt) {
            tabBtn.text = cnt
            tabBtn.scaleX = 1.3f
            tabBtn.scaleY = 1.3f
            tabBtn.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }
        val u = if (cur.started) cur.web.url else null
        if (u != null) {
            urlText.text = Uri.parse(u).host?.removePrefix("www.") ?: u
            val lock = u.startsWith("https")
            leftIcon.setImageResource(if (lock) R.drawable.ic_lock else R.drawable.ic_search)
            if (lock) leftIcon.setColorFilter(SUB) else leftIcon.clearColorFilter()
        } else {
            urlText.text = ""
            leftIcon.setImageResource(R.drawable.ic_search)
            leftIcon.clearColorFilter()
        }
        reloadBtn.visibility = if (cur.started) View.VISIBLE else View.GONE
        reloadBtn.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_reload)
    }

    private fun showTabs() {
        val dlg = Dialog(this, fullTheme())
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
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
            setTextColor(ONBLUE)
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
                    background = rr(if (t === cur) SEL else GRAY, 14)
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
            statusBarColor = BG
            navigationBarColor = BG
            decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        dlg.show()
    }

    // ---------- search overlay ----------
    private fun openSearch() {
        val dlg = Dialog(this, fullTheme())
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
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
            statusBarColor = BG
            navigationBarColor = BG
            decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            setWindowAnimations(android.R.style.Animation_Dialog)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
            "<body style='font-family:sans-serif;padding:32px;background:" + hex(BG) + ";color:" + hex(TXT) + "'>" +
            "<h2>This site can\u2019t be reached</h2><p style='color:#5F6368'>" +
            msg.replace("<", "&lt;") +
            "</p><p style='color:#5F6368'>Check your connection, or set a proxy from the menu (\u22EE).</p></body></html>"

    private fun configure(w: WebView, t: Tab) {
        w.setBackgroundColor(BG)
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
        applyWebTheme(w)
        w.overScrollMode = View.OVER_SCROLL_NEVER
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

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (blockAds && isAd(r.url.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
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
                    srl.isRefreshing = false
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
                    progress.setProgress(p, true)
                    if (p >= 100) srl.isRefreshing = false
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
                AlertDialog.Builder(this, dlgTheme()).setItems(arrayOf("Open in new tab", "Copy link")) { _, i ->
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
        val pm = PopupMenu(ContextThemeWrapper(this, if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light), anchor)
        val m = pm.menu
        m.add(0, 1, 0, "New tab")
        m.add(0, 2, 0, "Forward")
        m.add(0, 3, 0, "Reload")
        m.add(0, 4, 0, "Share")
        m.add(0, 5, 0, "Desktop site").apply { isCheckable = true; isChecked = desktop }
        m.add(0, 6, 0, "Block ads").apply { isCheckable = true; isChecked = blockAds }
        m.add(0, 7, 0, "Proxies")
        m.add(0, 8, 0, "Check for updates (v" + BuildConfig.VERSION_CODE + ")")
        m.add(0, 9, 0, "Clear browsing data")
        m.add(0, 10, 0, "Theme: " + listOf("Auto", "Light", "Dark")[prefs.getInt("theme", 0)])
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
                6 -> { blockAds = !blockAds; if (cur.started) cur.web.reload() }
                7 -> showProxies()
                8 -> checkUpdate(true)
                10 -> {
                    prefs.edit().putInt("theme", (prefs.getInt("theme", 0) + 1) % 3).apply()
                    restyle()
                }
                9 -> { wipe(); Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show() }
            }
            true
        }
        pm.show()
    }

    private fun setProxy(rule: String?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Toast.makeText(this, "Proxy not supported on this WebView", Toast.LENGTH_LONG).show()
            return
        }
        val pc = ProxyController.getInstance()
        prefs.edit().putString("active", rule ?: "").apply()
        if (rule.isNullOrEmpty()) {
            pc.clearProxyOverride({ it.run() }, { Toast.makeText(this, "Proxy off", Toast.LENGTH_SHORT).show() })
        } else {
            pc.setProxyOverride(ProxyConfig.Builder().addProxyRule(rule).build(), { it.run() }, {
                Toast.makeText(this, "Proxy on: $rule", Toast.LENGTH_SHORT).show()
                if (cur.started) cur.web.reload()
            })
        }
    }

    // ---------- misc ----------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private val prefs by lazy { getSharedPreferences("dx", Context.MODE_PRIVATE) }

    // ---------- ad block ----------
    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "2mdn.net", "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
        "juicyads.com", "trafficjunky.net", "adsterra.com", "revcontent.com", "mgid.com",
        "scorecardresearch.com", "quantserve.com", "moatads.com", "adform.net", "rubiconproject.com",
        "pubmatic.com", "openx.net", "casalemedia.com", "smartadserver.com", "amazon-adsystem.com",
        "serving-sys.com", "advertising.com", "yieldmo.com", "zedo.com", "media.net", "hilltopads.net",
        "clickadu.com", "onclickads.net", "adcash.com", "a-ads.com", "ad-maven.com", "admaven.com",
        "monetag.com", "profitablegatecpm.com", "highperformanceformat.com", "effectivegatecpm.com",
        "adskeeper.com", "adtng.com", "tsyndicate.com", "trafficstars.com", "bidvertiser.com",
        "yllix.com", "ero-advertising.com", "plugrush.com", "mookie1.com", "bluekai.com",
        "krxd.net", "demdex.net", "everesttech.net", "adition.com", "teads.tv", "sharethrough.com",
        "33across.com", "lijit.com", "sovrn.com", "contextweb.com", "indexww.com", "gumgum.com",
        "appnexus.com", "adroll.com", "hotjar.com", "mouseflow.com", "ads.yahoo.com", "adsafeprotected.com"
    )

    private fun isAd(host: String?): Boolean {
        if (host == null) return false
        var h: String = host
        while (true) {
            if (adHosts.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    // ---------- auto update (GitHub Releases) ----------
    private fun checkUpdate(manual: Boolean) {
        val repo = BuildConfig.GH_REPO
        if (repo.isEmpty()) {
            if (manual) toast("Updates work only for GitHub-built APKs")
            return
        }
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val ver = j.getString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                val assets = j.getJSONArray("assets")
                var apk: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
                }
                val link = apk
                runOnUiThread {
                    if (link != null && ver > BuildConfig.VERSION_CODE) askUpdate(ver, link)
                    else if (manual) toast("You have the latest version")
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { toast("Update check failed") }
            }
        }.start()
    }

    private fun askUpdate(ver: Int, url: String) {
        AlertDialog.Builder(this, dlgTheme())
            .setTitle("Update available")
            .setMessage("DxTech GO v$ver is ready to install.")
            .setPositiveButton("Update") { _, _ -> downloadApk(url) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadApk(url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingUrl = url
            toast("Allow \"Install unknown apps\" for DxTech GO, then go back")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val dlg = AlertDialog.Builder(this, dlgTheme()).setTitle("Downloading update").setView(bar).setCancelable(false).create()
        dlg.show()
        Thread {
            try {
                val dir = File(cacheDir, "update")
                dir.mkdirs()
                val f = File(dir, "DxTechGO.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                val total = c.contentLength
                c.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(32768)
                        var done = 0L
                        var n = 0
                        while (ins.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                runOnUiThread { bar.progress = p }
                            }
                        }
                    }
                }
                runOnUiThread { dlg.dismiss(); installApk(f) }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); toast("Download failed") }
            }
        }.start()
    }

    private fun installApk(f: File) {
        val uri = FileProvider.getUriForFile(this, "com.dxtech.go.fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(i)
    }

    // ---------- proxy manager ----------
    class Px(val name: String, val rule: String, val custom: Boolean) {
        var ms = -1L
    }

    private val testPool = Executors.newFixedThreadPool(8)

    private fun parsePx(line: String, custom: Boolean, defScheme: String = "http"): Px? {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        val name = if (l.contains("|")) l.substringBefore("|").trim() else ""
        var rule = (if (l.contains("|")) l.substringAfter("|") else l).trim()
        if (!rule.contains("://")) rule = "$defScheme://$rule"
        val hp = rule.substringAfter("://")
        if (!Regex("^[A-Za-z0-9.\\-]+:[0-9]{2,5}$").matches(hp)) return null
        return Px(if (name.isEmpty()) hp else name, rule, custom)
    }

    private fun loadAll(): MutableList<Px> {
        val out = mutableListOf(Px("Direct (no proxy)", "", false))
        try {
            assets.open("proxies.txt").bufferedReader().readLines().forEach { l -> parsePx(l, false)?.let { out.add(it) } }
        } catch (e: Exception) {
        }
        (prefs.getString("remote", "") ?: "").lines().forEach { l -> parsePx(l, false)?.let { out.add(it) } }
        (prefs.getString("custom", "") ?: "").lines().forEach { l -> parsePx(l, true)?.let { out.add(it) } }
        return out.distinctBy { it.rule }.toMutableList()
    }

    private fun removeCustom(rule: String) {
        val keep = (prefs.getString("custom", "") ?: "").lines().filter { parsePx(it, true)?.rule != rule && it.isNotBlank() }
        prefs.edit().putString("custom", keep.joinToString("\n")).apply()
    }

    private fun fetchRemote(done: (Int) -> Unit) {
        val urls = mutableListOf<Pair<String, String>>()
        if (BuildConfig.GH_REPO.isNotEmpty())
            urls.add("https://raw.githubusercontent.com/${BuildConfig.GH_REPO}/main/proxies.txt" to "http")
        urls.add("https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt" to "socks5")
        urls.add("https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/https/data.txt" to "http")
        Thread {
            val lines = mutableListOf<String>()
            for ((u, sch) in urls) {
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    var n = 0
                    for (raw in c.inputStream.bufferedReader().readLines()) {
                        val p = parsePx(raw, false, sch) ?: continue
                        lines.add(p.name + "|" + p.rule)
                        n++
                        if (n >= 30) break
                    }
                } catch (e: Exception) {
                }
            }
            prefs.edit().putString("remote", lines.joinToString("\n")).apply()
            runOnUiThread { done(lines.size) }
        }.start()
    }

    private fun toJavaProxy(rule: String): Proxy {
        if (rule.isEmpty()) return Proxy.NO_PROXY
        val scheme = if (rule.contains("://")) rule.substringBefore("://") else "http"
        val hp = rule.substringAfter("://")
        val host = hp.substringBeforeLast(":")
        val port = hp.substringAfterLast(":").toIntOrNull() ?: 8080
        return Proxy(
            if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved(host, port)
        )
    }

    private fun testPx(p: Px, done: () -> Unit) {
        testPool.execute {
            val t0 = System.currentTimeMillis()
            p.ms = try {
                val c = URL("https://www.gstatic.com/generate_204").openConnection(toJavaProxy(p.rule)) as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                if (c.responseCode in 200..399) System.currentTimeMillis() - t0 else -2L
            } catch (e: Exception) {
                -2L
            }
            runOnUiThread { done() }
        }
    }

    private fun applySavedProxy() {
        val a = prefs.getString("active", "") ?: ""
        if (a.isNotEmpty()) setProxy(a)
    }

    private fun addProxyDialog(saved: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
        }
        val n = EditText(this).apply { hint = "Name (optional)"; isSingleLine = true }
        val r = EditText(this).apply {
            hint = "socks5://host:port  or  http://host:port"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(n)
        box.addView(r)
        AlertDialog.Builder(this, dlgTheme()).setTitle("Add proxy").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val nm = n.text.toString().trim()
                val line = (if (nm.isNotEmpty()) "$nm|" else "") + r.text.toString().trim()
                val p = parsePx(line, true)
                if (p == null) {
                    toast("Invalid. Use scheme://host:port")
                } else {
                    val old = prefs.getString("custom", "") ?: ""
                    prefs.edit().putString("custom", (old + "\n" + p.name + "|" + p.rule).trim()).apply()
                    saved()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showProxies() {
        val dlg = Dialog(this, fullTheme())
        var items = loadAll()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val head = TextView(this).apply {
            text = "Proxies"
            textSize = 22f
            setTextColor(TXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(14), dp(18), dp(4))
        }
        val note = TextView(this).apply {
            text = "Free public proxies are untrusted. Use them for HTTPS sites only and never log in to sensitive accounts through them."
            textSize = 12f
            setTextColor(SUB)
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(4), dp(6), dp(8))
        }
        val bar = HorizontalScrollView(this).apply { addView(barRow) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }

        fun chip(label: String, filled: Boolean, click: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (filled) ONBLUE else BLUE)
            background = if (filled) rr(BLUE, 20) else rr(BG, 20, BLUE, 1)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            setOnClickListener { click() }
        }

        fun render() {
            list.removeAllViews()
            val active = prefs.getString("active", "") ?: ""
            val sorted = items.sortedWith(compareBy<Px>({ it.rule.isNotEmpty() }, { if (it.ms < 0) Long.MAX_VALUE else it.ms }))
            for (p in sorted) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(6), dp(8))
                    background = rr(if (p.rule == active) SEL else GRAY, 14)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                    setOnClickListener {
                        setProxy(if (p.rule.isEmpty()) null else p.rule)
                        render()
                    }
                }
                val txt = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                txt.addView(TextView(this).apply {
                    text = p.name
                    textSize = 15f
                    setTextColor(TXT)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (p.rule.isNotEmpty()) {
                    txt.addView(TextView(this).apply {
                        text = p.rule.substringBefore("://") + (if (p.custom) " \u00B7 yours" else "")
                        textSize = 12f
                        setTextColor(SUB)
                    })
                }
                val msView = TextView(this).apply {
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(8), 0, dp(8), 0)
                    if (p.ms >= 0) {
                        text = "${p.ms} ms"
                        setTextColor(if (p.ms < 1000) Color.parseColor("#188038") else Color.parseColor("#E37400"))
                    } else if (p.ms == -2L) {
                        text = "fail"
                        setTextColor(Color.parseColor("#D93025"))
                    } else {
                        text = "-"
                        setTextColor(SUB)
                    }
                }
                row.addView(txt)
                row.addView(msView)
                if (p.custom) {
                    val x = icon(R.drawable.ic_close, 40, 10)
                    x.setOnClickListener {
                        removeCustom(p.rule)
                        items = loadAll()
                        render()
                    }
                    row.addView(x)
                }
                list.addView(row)
            }
        }

        fun testAll(then: (() -> Unit)?) {
            val left = AtomicInteger(items.size)
            items.forEach { it.ms = -1L }
            render()
            for (p in items.toList()) {
                testPx(p) {
                    render()
                    if (left.decrementAndGet() == 0) then?.invoke()
                }
            }
        }

        fun auto() {
            toast("Testing proxies...")
            testAll {
                val best = items.filter { it.rule.isNotEmpty() && it.ms >= 0 }.minByOrNull { it.ms }
                if (best == null) {
                    toast("No working proxy found")
                } else {
                    setProxy(best.rule)
                    render()
                }
            }
        }

        barRow.addView(chip("Auto fastest", true) { auto() })
        barRow.addView(chip("Test speed", false) { testAll(null) })
        barRow.addView(chip("+ Add", false) { addProxyDialog { items = loadAll(); render() } })
        barRow.addView(chip("Refresh list", false) {
            toast("Fetching list...")
            fetchRemote { n ->
                items = loadAll()
                render()
                toast("$n proxies loaded")
            }
        })

        col.addView(head)
        col.addView(note)
        col.addView(bar)
        col.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(col)
        dlg.window?.apply {
            setLayout(-1, -1)
            statusBarColor = BG
            navigationBarColor = BG
            decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        dlg.show()
        render()
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
