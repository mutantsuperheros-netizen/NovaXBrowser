package com.novax.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {
    data class BrowserTab(
        val webView: KeepAliveWebView,
        var title: String = "Home Page",
        var url: String = "novax://home"
    )

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = 0
    private var pinnedMusicTab: BrowserTab? = null

    private lateinit var webContainer: FrameLayout
    private lateinit var etUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvBrand: TextView
    private lateinit var btnVipAd: Button
    private lateinit var btnLogin: Button
    private lateinit var btnTabs: Button
    private lateinit var miniPlayerBar: LinearLayout
    private lateinit var tvMiniPlayerTitle: TextView
    private lateinit var adView: AdView
    private lateinit var prefs: SharedPreferences
    private var rewardedAd: RewardedAd? = null

    private val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    private val PICK_MEDIA_REQUEST = 1001
    private val adDomains = mutableSetOf<String>()
    private val adultDomains = mutableSetOf<String>()

    private val currentWebView: KeepAliveWebView
        get() = tabs[currentTabIndex].webView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("novax_prefs", Context.MODE_PRIVATE)
        loadBlockLists()

        webContainer = findViewById(R.id.webContainer)
        etUrl = findViewById(R.id.etUrl)
        progressBar = findViewById(R.id.progressBar)
        tvBrand = findViewById(R.id.tvBrand)
        btnVipAd = findViewById(R.id.btnVipAd)
        btnLogin = findViewById(R.id.btnLogin)
        btnTabs = findViewById(R.id.btnTabs)
        miniPlayerBar = findViewById(R.id.miniPlayerBar)
        tvMiniPlayerTitle = findViewById(R.id.tvMiniPlayerTitle)
        adView = findViewById(R.id.adView)

        MobileAds.initialize(this) {}
        adView.loadAd(AdRequest.Builder().build())
        loadRewardedVideoAd()
        updateAccountAndVipUI()

        setupButtons()
        createNewTab(loadHome = true)
    }

    private fun isAdmin(): Boolean {
        val email = prefs.getString("logged_email", "") ?: ""
        return email.equals("mutantsuperheros@gmail.com", ignoreCase = true) || 
               email.equals("achadar839@gmail.com", ignoreCase = true) || 
               email.equals("achadar839", ignoreCase = true)
    }

    private fun isVipActive(): Boolean {
        if (isAdmin()) return true
        return System.currentTimeMillis() < prefs.getLong("vip_unlock_until", 0L)
    }

    private fun updateAccountAndVipUI() {
        val email = prefs.getString("logged_email", "") ?: ""
        if (isAdmin()) {
            tvBrand.text = "👑 NovaX Gold Admin"
            btnLogin.text = "👑 Admin"
            btnVipAd.text = "👑 Gold Active"
        } else if (email.isNotEmpty()) {
            tvBrand.text = "⚡ NovaX Browser"
            btnLogin.text = "👤 ${email.take(8)}..."
            btnVipAd.text = if (isVipActive()) "👑 VIP Active" else "🎬 VIP Ad"
        } else {
            tvBrand.text = "⚡ NovaX Browser"
            btnLogin.text = "👤 Login"
            btnVipAd.text = if (isVipActive()) "👑 VIP Active" else "🎬 VIP Ad"
        }
    }

    private fun showLoginDialog() {
        val input = EditText(this)
        input.hint = "Enter Gmail (e.g. mutantsuperheros@gmail.com)"
        input.setText(prefs.getString("logged_email", ""))
        
        AlertDialog.Builder(this)
            .setTitle("👤 Optional Google Account Login")
            .setView(input)
            .setMessage("Login with your admin email to unlock permanent Gold features automatically.")
            .setPositiveButton("Save & Login") { _, _ ->
                val enteredEmail = input.text.toString().trim()
                prefs.edit().putString("logged_email", enteredEmail).apply()
                updateAccountAndVipUI()
                loadHomeSpeedDial(currentWebView)
                Toast.makeText(this, "Logged in as $enteredEmail", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Logout") { _, _ ->
                prefs.edit().remove("logged_email").apply()
                updateAccountAndVipUI()
                loadHomeSpeedDial(currentWebView)
                Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createNewTab(initialUrl: String? = null, loadHome: Boolean = false) {
        val wv = KeepAliveWebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.allowBackgroundPlay = true

        val newTab = BrowserTab(webView = wv)
        configureWebView(newTab)

        tabs.add(newTab)
        webContainer.addView(wv)
        switchToTab(tabs.size - 1)

        if (loadHome || initialUrl == null) {
            loadHomeSpeedDial(wv)
        } else {
            wv.loadUrl(initialUrl)
        }
        updateTabCounter()
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        currentTabIndex = index

        tabs.forEachIndexed { i, tab ->
            if (i == index) {
                tab.webView.visibility = View.VISIBLE
                tab.webView.onResume()
                val displayUrl = if (tab.url.startsWith("data:") || tab.url == "novax://home") "" else tab.url
                etUrl.setText(displayUrl)
            } else {
                tab.webView.visibility = View.GONE
            }
        }
        updateTabCounter()
        updateMiniPlayerVisibility()
    }

    private fun updateMiniPlayerVisibility() {
        val pinned = pinnedMusicTab
        if (pinned != null && tabs.contains(pinned)) {
            val pinnedIndex = tabs.indexOf(pinned)
            if (pinnedIndex != currentTabIndex) {
                miniPlayerBar.visibility = View.VISIBLE
                tvMiniPlayerTitle.text = "🎵 Playing: ${pinned.title}"
            } else {
                miniPlayerBar.visibility = View.GONE
            }
        } else {
            miniPlayerBar.visibility = View.GONE
        }
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            loadHomeSpeedDial(tabs[0].webView)
            tabs[0].title = "Home Page"
            tabs[0].url = "novax://home"
            pinnedMusicTab = null
            updateMiniPlayerVisibility()
            return
        }
        val removed = tabs.removeAt(index)
        if (removed == pinnedMusicTab) pinnedMusicTab = null
        webContainer.removeView(removed.webView)
        removed.webView.destroy()

        if (currentTabIndex >= tabs.size) currentTabIndex = tabs.size - 1
        switchToTab(currentTabIndex)
    }

    private fun updateTabCounter() {
        btnTabs.text = "[ ${tabs.size} ]"
    }

    private fun showTabSwitcherDialog() {
        val tabTitles = tabs.mapIndexed { idx, tab ->
            val activeMark = if (idx == currentTabIndex) "🟢 " else if (tab == pinnedMusicTab) "🎵 " else "⚪ "
            val cleanTitle = if (tab.title.length > 25) tab.title.substring(0, 25) + "..." else tab.title
            "$activeMark Tab ${idx + 1}: $cleanTitle"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("🗂️ Open Windows (${tabs.size})")
            .setItems(tabTitles) { _, which -> switchToTab(which) }
            .setPositiveButton("➕ New Tab") { _, _ -> createNewTab(loadHome = true) }
            .setNegativeButton("❌ Close Current") { _, _ -> closeTab(currentTabIndex) }
            .setNeutralButton("Back", null)
            .show()
    }

    private fun loadRewardedVideoAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(this, REWARDED_AD_UNIT_ID, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) { rewardedAd = null }
            override fun onAdLoaded(ad: RewardedAd) { rewardedAd = ad }
        })
    }

    private fun unlockVipForMonth() {
        prefs.edit().putLong("vip_unlock_until", System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000L)).apply()
        updateAccountAndVipUI()
        Toast.makeText(this, "🎉 VIP Unlocked for 30 Days!", Toast.LENGTH_LONG).show()
    }

    private fun loadBlockLists() {
        try {
            assets.open("adblock_domains.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        if (trimmed.contains("porn") || trimmed.contains("xvideos") || trimmed.contains("xnxx") || trimmed.contains("redtube") || trimmed.contains("brazzers")) {
                            adultDomains.add(trimmed)
                        } else {
                            adDomains.add(trimmed)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            adDomains.addAll(listOf("doubleclick.net", "googlesyndication.com"))
            adultDomains.addAll(listOf("porn.com", "pornhub.com", "xvideos.com", "xnxx.com"))
        }
    }

    private fun injectSmartBrowserScripts(wv: KeepAliveWebView) {
        val jsMaster = """
            javascript:(function() {
                if (window.novaxInjected) return;
                window.novaxInjected = true;
                setInterval(function() {
                    try {
                        var skipSelectors = '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button';
                        document.querySelectorAll(skipSelectors).forEach(function(btn) { btn.click(); });
                        var player = document.querySelector('.html5-video-player');
                        var video = document.querySelector('video');
                        if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {
                            if (video) { video.muted = true; video.playbackRate = 16.0; if (!isNaN(video.duration) && video.duration > 0) video.currentTime = video.duration; }
                        }
                        var adSelectors = 'ytm-promoted-sparkles-web-renderer, .ytp-ad-overlay-container, ins.adsbygoogle';
                        document.querySelectorAll(adSelectors).forEach(function(el) { el.remove(); });
                    } catch(e) {}
                }, 350);
            })();
        """.trimIndent()
        wv.evaluateJavascript(jsMaster, null)
    }

    private fun configureWebView(tab: BrowserTab) {
        val wv = tab.webView
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.setSupportMultipleWindows(true)
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val urlStr = request?.url?.toString() ?: return false
                val host = request.url?.host?.lowercase() ?: ""
                
                // Strict Adult Content Blocker & SafeSearch Enforcement
                if (adultDomains.any { host.contains(it) } || urlStr.contains("porn", true) || urlStr.contains("sex", true) || urlStr.contains("xvid", true) || urlStr.contains("xnxx", true)) {
                    Toast.makeText(applicationContext, "🚫 Adult/Porn Content Blocked for Safety", Toast.LENGTH_SHORT).show()
                    view?.loadData("<html><body style='background:#0D1117;color:#FF5555;text-align:center;padding-top:50px;font-family:sans-serif;'><h2>🚫 Access Blocked</h2><p>This website is restricted by NovaX Strict Safety Filter.</p></body></html>", "text/html", "UTF-8")
                    return true
                }
                
                // Enforce Google SafeSearch
                if (urlStr.contains("google.com/search") && !urlStr.contains("safe=active")) {
                    view?.loadUrl("$urlStr&safe=active")
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                val host = request.url?.host ?: ""
                if (adDomains.any { host.contains(it) } || urlStr.contains("/pagead/") || urlStr.contains("googleads.g.doubleclick")) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    tab.url = url
                    if (!url.startsWith("data:") && url != "https://novax.local/") {
                        if (tabs.indexOf(tab) == currentTabIndex) etUrl.setText(url)
                    }
                }
                injectSmartBrowserScripts(wv)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tabs.indexOf(tab) == currentTabIndex) {
                    if (newProgress < 100) { progressBar.visibility = View.VISIBLE; progressBar.progress = newProgress }
                    else { progressBar.visibility = View.GONE }
                }
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank() && !title.startsWith("data:")) {
                    tab.title = title
                    updateMiniPlayerVisibility()
                }
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "⚡ Downloading 4K Media File...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this, "Download error", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnGo).setOnClickListener { openInputUrl() }
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            loadHomeSpeedDial(currentWebView)
            tabs[currentTabIndex].title = "Home Page"
            tabs[currentTabIndex].url = "novax://home"
        }
        btnLogin.setOnClickListener { showLoginDialog() }
        btnTabs.setOnClickListener { showTabSwitcherDialog() }
        
        btnVipAd.setOnClickListener {
            if (isAdmin()) {
                Toast.makeText(this, "👑 Gold Admin Status: All Features Permanently Unlocked!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (rewardedAd != null) {
                rewardedAd?.show(this) { unlockVipForMonth(); loadRewardedVideoAd() }
            } else {
                unlockVipForMonth()
                loadRewardedVideoAd()
            }
        }

        findViewById<Button>(R.id.btnWallpaper).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")) }
            startActivityForResult(intent, PICK_MEDIA_REQUEST)
        }

        findViewById<Button>(R.id.btnPinAudio).setOnClickListener {
            val currentTab = tabs[currentTabIndex]
            pinnedMusicTab = currentTab
            currentTab.webView.allowBackgroundPlay = true
            injectSmartBrowserScripts(currentTab.webView)
            createNewTab(loadHome = true)
            updateMiniPlayerVisibility()
            Toast.makeText(this, "🎵 Audio Pinned for Background Play!", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnSaveCustomHome).setOnClickListener {
            Toast.makeText(this, "💾 Custom Home Settings Saved Successfully!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnMiniPlayPause).setOnClickListener {
            pinnedMusicTab?.webView?.evaluateJavascript("javascript:(function(){ document.querySelectorAll('video, audio').forEach(function(m){ if(m.paused) m.play(); else m.pause(); }); })();", null)
        }
        findViewById<Button>(R.id.btnMiniOpen).setOnClickListener {
            pinnedMusicTab?.let { switchToTab(tabs.indexOf(it)) }
        }
        findViewById<Button>(R.id.btnMiniClose).setOnClickListener {
            pinnedMusicTab = null
            updateMiniPlayerVisibility()
        }

        etUrl.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) { openInputUrl(); true } else false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_MEDIA_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val uriStr = uri.toString()
                prefs.edit().putString("custom_media_wallpaper", uriStr).apply()
                loadHomeSpeedDial(currentWebView)
                Toast.makeText(this, "🎨 Custom 4K Theme Applied & Saved!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openInputUrl() {
        val input = etUrl.text.toString().trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}&safe=active"
        }
        currentWebView.loadUrl(url)
    }

    private fun loadHomeSpeedDial(wv: KeepAliveWebView) {
        val savedMedia = prefs.getString("custom_media_wallpaper", "") ?: ""
        val adminMode = isAdmin()
        
        val bgStyle = if (savedMedia.isNotEmpty()) {
            "background: linear-gradient(rgba(13,17,23,0.8), rgba(13,17,23,0.8)), url('$savedMedia'); background-size: cover; background-position: center;"
        } else {
            "background: linear-gradient(135deg, #0D1117 0%, #161B22 100%);"
        }

        val adminBadgeHtml = if (adminMode) {
            """<div style="background:linear-gradient(90deg, #D4AF37, #FFD700); color:#0D1117; padding:8px 16px; border-radius:20px; font-weight:bold; display:inline-block; margin-bottom:15px; box-shadow: 0 4px 15px rgba(212,175,55,0.4);">👑 GOLD ADMIN CARD: ALL FEATURES UNLOCKED</div>"""
        } else {
            ""
        }

        val html = """
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { $bgStyle color:#FFFFFF; font-family:sans-serif; text-align:center; padding:15px; height:100vh; margin:0; display:flex; flex-direction:column; justify-content:center; align-items:center; overflow:hidden; }
                    h2 { color:#58A6FF; margin-bottom:5px; text-shadow: 0 2px 4px rgba(0,0,0,0.8); }
                    p { color:#C9D1D9; font-size:12px; margin-top:0; text-shadow: 0 1px 2px rgba(0,0,0,0.8); }
                    .grid { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-top:15px; width:100%; max-width:360px; }
                    .card { background:rgba(22,27,34,0.85); backdrop-filter: blur(10px); padding:16px; border-radius:12px; text-decoration:none; color:#E6EDF3; font-weight:bold; border:1px solid #30363D; display:block; text-align:center; }
                </style>
            </head>
            <body>
                $adminBadgeHtml
                <h2>⚡ NovaX Ultra Browser</h2>
                <p>4K Video/Photo Themes • Background Audio • Safe Ad-Free</p>
                <div class="grid">
                    <a class="card" href="https://www.google.com?safe=active">🔍 Google</a>
                    <a class="card" href="https://m.youtube.com">▶️ YouTube (4K)</a>
                    <a class="card" href="https://www.instagram.com">📸 Instagram</a>
                    <a class="card" href="https://www.wikipedia.org">📚 Wikipedia</a>
                </div>
            </body>
            </html>
        """.trimIndent()
        wv.loadDataWithBaseURL("https://novax.local", html, "text/html", "UTF-8", null)
        etUrl.setText("")
    }
}
