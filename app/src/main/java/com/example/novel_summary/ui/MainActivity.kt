package com.example.novel_summary.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import com.example.novel_summary.R
import com.example.novel_summary.data.model.Bookmark
import com.example.novel_summary.data.model.History
import com.example.novel_summary.databinding.ActivityMainBinding
import com.example.novel_summary.ui.viewmodel.MainViewModel
import com.example.novel_summary.utils.ContentHolder
import com.example.novel_summary.utils.ToastUtils
import com.example.novel_summary.utils.UrlUtils
import com.example.novel_summary.utils.WebViewUtils
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: SharedPreferences

    private var currentWebViewJob: Job? = null
    private var isBookmarked = false
    private var isLoading = false
    private var isExitDialogShowing = false
    private var webViewUrl: String? = null
    private var webViewTitle: String? = null

    private var isDarkMode: Boolean = true

   // val settingsButton: Button = findViewById(R.id.settings_button)


    companion object {
        private const val PREF_FILE = "novel_prefs"
        private const val PREF_DARK_MODE = "dark_mode"
    }

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean(PREF_DARK_MODE, true)




        applySystemThemeOverride()
        TopMenuHide()
        setupWebView()
        setupDarkModeToggle()
        setupSearchBar()
        setupNavigationButtons()
        setupSummarizeButton()
        setupBookmarkButton()
        setupHomeButton()
        setupRefreshButton()
        setupNativeHomepage()
        setupBackPressedCallback()
        updateDarkModeToggleIcon()

        updateDarkModeToggleIcon()

        setupSettingsButton()

        if (savedInstanceState != null) {
            // Restore WebView state if the Activity was recreated (e.g. theme toggle)
            val wasHomePageVisible = savedInstanceState.getBoolean("is_homepage_visible", true)
            if (wasHomePageVisible) {
                loadHomePage()
            } else {
                binding.nativeHomepage.root.visibility = android.view.View.GONE
                binding.webView.visibility = android.view.View.VISIBLE
                binding.btnSummarize.isVisible = true
                binding.btnBookmark.isVisible = true
                binding.webView.restoreState(savedInstanceState)
                webViewUrl = binding.webView.url

            }
        } else {
            intent.getStringExtra("SELECTED_URL")?.let { url ->
                webViewUrl = url
                loadUrl(url)
            } ?: loadHomePage()
        }
    }


    // ✅ NEW METHOD: Settings Button Handler
    private fun setupSettingsButton() {
        try {
            // Find the settings button from home page layout
            val settingsButton = findViewById<android.widget.ImageButton>(R.id.settings_button)
            settingsButton?.setOnClickListener {
                navigateToSettings()
            }
        } catch (e: Exception) {
            android.util.Log.w("SettingsButton", "Settings button not found in current layout")
        }
    }

    // ✅ NAVIGATION METHOD
    private fun navigateToSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        overridePendingTransition(
            android.R.anim.slide_in_left,
            android.R.anim.slide_out_right
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("SELECTED_URL")?.let { url ->
            webViewUrl = url
            loadUrl(url)
        }
    }

    override fun onResume() {
        super.onResume()
        if (webViewUrl != null) updateBookmarkState(webViewUrl)
    }

    override fun onDestroy() {
        if (isFinishing) {
            currentWebViewJob?.cancel()
            binding.webView.destroy()
        }
        super.onDestroy()
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_homepage_visible", binding.nativeHomepage.root.isVisible)
        binding.webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        binding.webView.restoreState(savedInstanceState)
    }

    // ─────────────────────────────────────────────────────────────
    // DARK MODE TOGGLE
    // ─────────────────────────────────────────────────────────────

    private fun setupDarkModeToggle() {
        binding.btnDarkModeToggle.setOnClickListener {
            isDarkMode = !isDarkMode
            prefs.edit().putBoolean(PREF_DARK_MODE, isDarkMode).apply()
            applySystemThemeOverride()
            updateDarkModeToggleIcon()
            applyCurrentTheme()
            ToastUtils.showShort(this, if (isDarkMode) "Dark mode ON" else "Light mode ON")
        }
    }

    private fun applySystemThemeOverride() {
        delegate.setLocalNightMode(                   // ✅ only affects MainActivity
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun updateDarkModeToggleIcon() {
        binding.btnDarkModeToggle.setImageResource(
            if (isDarkMode) R.drawable.sunny_24 else R.drawable.moon_stars_24
        )
    }

    private fun applyCurrentTheme() {
        binding.webView.setBackgroundColor(
            if (isDarkMode) android.graphics.Color.parseColor("#121212")
            else android.graphics.Color.WHITE
        )
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                binding.webView.settings.forceDark =
                    if (isDarkMode) android.webkit.WebSettings.FORCE_DARK_ON
                    else android.webkit.WebSettings.FORCE_DARK_OFF
            }
        } catch (e: Exception) { /* ignore */ }

        // Homepage handles its own theming via system night mode — skip WebView injection
        if (binding.nativeHomepage.root.isVisible) return

        if (isDarkMode) WebViewUtils.injectDarkMode(binding.webView)
        else { WebViewUtils.removeDarkMode(binding.webView); WebViewUtils.injectAdBlockOnly(binding.webView) }
    }

    // ─────────────────────────────────────────────────────────────
    // TOP BAR
    // ─────────────────────────────────────────────────────────────

    private fun TopMenuHide() {
        binding.topmenuhide.setOnClickListener {
            binding.topBarContainer.isVisible = !binding.topBarContainer.isVisible
        }
    }

    // ─────────────────────────────────────────────────────────────
    // WEBVIEW SETUP
    // ─────────────────────────────────────────────────────────────

    private fun setupWebView() {
        WebViewUtils.configureWebView(binding.webView)

        binding.webView.setBackgroundColor(
            if (isDarkMode) android.graphics.Color.parseColor("#121212") else android.graphics.Color.WHITE
        )

        binding.webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (WebViewUtils.shouldBlockRequest(url))
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val bgColor = if (isDarkMode) "#121212" else "#ffffff"
                val textColor = if (isDarkMode) "#e0e0e0" else "#121212"
                view?.evaluateJavascript("""
                    (function(){
                        document.documentElement.style.backgroundColor='$bgColor';
                        if(document.body){document.body.style.backgroundColor='$bgColor';document.body.style.color='$textColor';}
                    })();
                """.trimIndent()) {}
                isLoading = true
                binding.progressBar.isVisible = true
                binding.progressBar.progress = 0
                updateBookmarkState(url)
                updateNavigationButtons()
                binding.btnSummarize.isEnabled = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
                binding.progressBar.isVisible = false
                binding.progressBar.progress = 100
                webViewUrl = url
                webViewTitle = view?.title

                if (view != null) {
                    if (isDarkMode) WebViewUtils.injectDarkMode(view) else WebViewUtils.injectAdBlockOnly(view)
                    injectContentPadding(view)
                }

                currentWebViewJob?.cancel()
                currentWebViewJob = CoroutineScope(Dispatchers.Main).launch {
                    view?.evaluateJavascript("(function(){return document.title;})()") { title ->
                        val pageTitle = if (title == "\"\"" || title == "null") UrlUtils.extractTitleFromUrl(url ?: "") else title.trim('"')
                        if (url != null) CoroutineScope(Dispatchers.IO).launch { viewModel.saveToHistory(History(url = url, title = pageTitle)) }
                    }
                }

                runOnUiThread {
                    binding.searchEditText.setText(url ?: "")
                    updateBookmarkState(url)
                    updateNavigationButtons()
                    binding.btnSummarize.isEnabled = url != null
                    binding.btnSummarize.isVisible = true
                    binding.btnBookmark.isVisible = true
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    runOnUiThread {
                        ToastUtils.showError(this@MainActivity, "Failed to load: ${error?.description}")
                        binding.progressBar.isVisible = false
                        isLoading = false
                        binding.btnSummarize.isEnabled = false
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (e: Exception) { ToastUtils.showError(this@MainActivity, "Cannot open this link") }
                    return true
                }
                return false
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                binding.btnSummarize.alpha = if (newProgress == 100) 1.0f else 0.5f
                if (newProgress == 100) updateNavigationButtons()
                binding.btnRefresh.isEnabled = newProgress == 100
            }
        }
    }

    private fun injectContentPadding(view: WebView) {
        view.evaluateJavascript("""
            (function(){
                document.body.style.paddingTop='50px';document.body.style.paddingBottom='40px';document.body.style.boxSizing='border-box';
                document.querySelectorAll('header,nav,.header,.navbar,.top-bar').forEach(function(h){if(window.getComputedStyle(h).position==='fixed')h.style.top='50px';});
                document.querySelectorAll('footer,.footer,.bottom-bar').forEach(function(f){if(window.getComputedStyle(f).position==='fixed')f.style.bottom='40px';});
            })();
        """.trimIndent()) {}
    }

    // ─────────────────────────────────────────────────────────────
    // REFRESH
    // ─────────────────────────────────────────────────────────────

    private fun setupRefreshButton() {
        binding.btnRefresh.setOnClickListener { binding.webView.reload(); ToastUtils.showShort(this, "Refreshing…") }
    }

    // ─────────────────────────────────────────────────────────────
    // SEARCH BAR
    // ─────────────────────────────────────────────────────────────

    private fun setupSearchBar() {
        binding.searchEditText.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val input = v.text.toString().trim()
                if (input.isNotEmpty()) { loadUrl(UrlUtils.normalizeUrl(input)); hideKeyboard(); return@setOnEditorActionListener true }
                false
            } else false
        }
        binding.btnGo.setOnClickListener {
            val input = binding.searchEditText.text.toString().trim()
            if (input.isNotEmpty()) { loadUrl(UrlUtils.normalizeUrl(input)); hideKeyboard() }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // NAVIGATION
    // ─────────────────────────────────────────────────────────────

    private fun setupNavigationButtons() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_backk -> { 
                    if (binding.webView.visibility == android.view.View.VISIBLE && binding.webView.canGoBack()) {
                        binding.webView.goBack() 
                    } else if (binding.webView.visibility == android.view.View.VISIBLE) {
                        loadHomePage()
                    }
                    true 
                }

                R.id.menu_forwardd -> { if (binding.webView.canGoForward()) binding.webView.goForward() else ToastUtils.showShort(this, "No forward page"); true }
                R.id.menu_bookmarks -> { startActivity(Intent(this, Activity_Boolmarks::class.java)); true }
                R.id.menu_history -> { startActivity(Intent(this, Activity_History::class.java)); true }
                R.id.menu_library -> { startActivity(Intent(this, Activity_Library::class.java)); true }
                else -> false
            }
        }
    }

    private fun updateNavigationButtons() {
        binding.bottomNavigationView.menu.findItem(R.id.menu_backk)?.setIcon(R.drawable.outline_arrow_back_ios_24)
        binding.bottomNavigationView.menu.findItem(R.id.menu_forwardd)?.setIcon(R.drawable.outline_arrow_forward_ios_24)
        binding.btnRefresh.isEnabled = !isLoading
        binding.btnRefresh.alpha = if (!isLoading) 1.0f else 0.5f
    }

    // ─────────────────────────────────────────────────────────────
    // BOOKMARK
    // ─────────────────────────────────────────────────────────────

    private fun setupBookmarkButton() {
        binding.btnBookmark.setOnClickListener {
            val currentUrl = binding.webView.url ?: return@setOnClickListener
            val currentTitle = binding.webView.title ?: UrlUtils.extractTitleFromUrl(currentUrl)
            if (isBookmarked) {
                showConfirmationDialog("Remove Bookmark", "Remove this bookmark?") {
                    CoroutineScope(Dispatchers.IO).launch {
                        viewModel.removeBookmark(currentUrl)
                        runOnUiThread { isBookmarked = false; binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_outline); ToastUtils.showSuccess(this@MainActivity, "Bookmark removed") }
                    }
                }
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    viewModel.saveBookmark(Bookmark(url = currentUrl, title = currentTitle))
                    runOnUiThread { isBookmarked = true; binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_filled); ToastUtils.showSuccess(this@MainActivity, "Bookmarked!") }
                }
            }
        }
    }

    private fun updateBookmarkState(url: String?) {
        if (url == null) { binding.btnBookmark.isVisible = false; isBookmarked = false; return }
        binding.btnBookmark.isVisible = true
        CoroutineScope(Dispatchers.IO).launch {
            val bookmark = viewModel.getBookmarkByUrl(url)
            runOnUiThread {
                isBookmarked = bookmark != null
                binding.btnBookmark.setImageResource(if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HOME BUTTON
    // ─────────────────────────────────────────────────────────────

    private fun setupHomeButton() {
        binding.btnHome.setOnClickListener { loadHomePage() }
    }

    // ─────────────────────────────────────────────────────────────
    // SUMMARIZE
    // ─────────────────────────────────────────────────────────────

    private fun setupSummarizeButton() {
        binding.btnSummarize.setOnClickListener {
            if (isLoading) { ToastUtils.showShort(this, "Please wait for page to finish loading"); return@setOnClickListener }
            val url = binding.webView.url ?: return@setOnClickListener
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Extracting Content").setMessage("Extracting text from page…").setCancelable(false).create()
            dialog.show()
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(1000)
                binding.webView.evaluateJavascript(WebViewUtils.extractCleanText(binding.webView)) { content ->
                    dialog.dismiss()
                    if (content != "\"\"" && content != "null" && content.length > 100) {
                        ContentHolder.setContent(content.trim('"'), url, binding.webView.title ?: "Untitled")
                        startActivity(Intent(this@MainActivity, ActivitySummary::class.java))
                    } else runOnUiThread { showContentExtractionOptions(url) }
                }
            }
        }
    }

    private fun showContentExtractionOptions(url: String) {
        AlertDialog.Builder(this).setTitle("Content Extraction Failed").setMessage("Could not automatically extract content. Try:")
            .setItems(arrayOf("Retry Extraction", "Select Content Manually", "View Page Source")) { _, which ->
                when (which) { 0 -> retryContentExtraction(url); 1 -> showManualSelectionDialog(); 2 -> showPageSource() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun retryContentExtraction(url: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Retrying…").setMessage("Waiting for content…").setCancelable(false).create()
        dialog.show()
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(3000)
            binding.webView.evaluateJavascript(WebViewUtils.extractCleanText(binding.webView)) { content ->
                dialog.dismiss()
                if (content != "\"\"" && content != "null" && content.length > 100) {
                    ContentHolder.setContent(content.trim('"'), url, binding.webView.title ?: "Untitled")
                    startActivity(Intent(this@MainActivity, ActivitySummary::class.java))
                } else ToastUtils.showError(this@MainActivity, "Still could not extract. Try manual selection.")
            }
        }
    }

    private fun showManualSelectionDialog() {
        AlertDialog.Builder(this).setTitle("Manual Content Selection").setMessage("Enter CSS selector (e.g., '.chapter-content'):")
            .setView(androidx.appcompat.widget.AppCompatEditText(this).apply { hint = "CSS selector"; id = android.R.id.edit })
            .setPositiveButton("Extract") { dlg, _ ->
                val sel = (dlg as AlertDialog).findViewById<androidx.appcompat.widget.AppCompatEditText>(android.R.id.edit)?.text.toString().trim()
                if (sel.isNotEmpty()) extractWithCustomSelector(sel)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun extractWithCustomSelector(selector: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Extracting…").setMessage("Using: $selector").setCancelable(false).create()
        dialog.show()
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(500)
            binding.webView.evaluateJavascript("""(function(){try{var el=document.querySelector('$selector');return el?el.innerText.trim():'NOT_FOUND';}catch(e){return 'ERROR: '+e.message;}})()""") { result ->
                dialog.dismiss()
                val clean = result.trim('"')
                if (clean == "NOT_FOUND" || clean.startsWith("ERROR:") || clean.length < 100) ToastUtils.showError(this@MainActivity, "Selector didn't work. Try another.")
                else { ContentHolder.setContent(clean, binding.webView.url ?: "", binding.webView.title ?: "Untitled"); startActivity(Intent(this@MainActivity, ActivitySummary::class.java)) }
            }
        }
    }

    private fun showPageSource() {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Loading source…").setCancelable(true).create()
        dlg.show()
        binding.webView.evaluateJavascript("(function(){return document.body.innerHTML.substring(0,2000);})()") { html ->
            dlg.dismiss()
            AlertDialog.Builder(this).setTitle("HTML Structure (first 2000 chars)").setMessage(html.trim('"')).setPositiveButton("OK", null).show()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // BACK PRESS
    // ─────────────────────────────────────────────────────────────

    private fun setupBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.nativeHomepage.root.isVisible -> { if (!isExitDialogShowing) showExitConfirmationDialog() }
                    binding.webView.visibility == android.view.View.VISIBLE && binding.webView.canGoBack() -> {
                        binding.webView.goBack()
                    }
                    else -> loadHomePage()
                }
            }
        })
    }

    // ─────────────────────────────────────────────────────────────
    // NATIVE HOMEPAGE
    // ─────────────────────────────────────────────────────────────
    private fun setupNativeHomepage() {
        with(binding.nativeHomepage) {
            homeSearchInput.setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    val input = v.text.toString().trim()
                    if (input.isNotEmpty()) { loadUrl(UrlUtils.normalizeUrl(input)); hideKeyboard(); return@setOnEditorActionListener true }
                }
                false
            }


            cardBookmarks.setOnClickListener { startActivity(Intent(this@MainActivity, Activity_Boolmarks::class.java)) }
            cardLibrary.setOnClickListener { startActivity(Intent(this@MainActivity, Activity_Library::class.java)) }
            cardHistory.setOnClickListener { startActivity(Intent(this@MainActivity, Activity_History::class.java)) }
            cardSiteRoyalRoad.setOnClickListener { loadUrl("https://www.royalroad.com/home") }
            cardSiteWebNovel.setOnClickListener { loadUrl("https://www.webnovel.com") }
            cardSiteScribbleHub.setOnClickListener { loadUrl("https://www.scribblehub.com") }
            cardSiteWuxiaWorld.setOnClickListener { loadUrl("https://www.wuxiaworld.com") }
            cardSiteWattpad.setOnClickListener { loadUrl("https://www.wattpad.com/") }
            cardSiteWTRLab.setOnClickListener { loadUrl("https://wtr-lab.com/en") }

        }
    }



    private fun loadHomePage() {

        binding.searchEditText.setText("")
        binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_outline)
        isBookmarked = false
        binding.webView.visibility = android.view.View.GONE
        binding.nativeHomepage.root.visibility = android.view.View.VISIBLE
        binding.btnSummarize.isVisible = false
        binding.btnBookmark.isVisible = false
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun loadUrl(url: String) {
        if (UrlUtils.isValidUrl(url)) {
            val wasOnHomePage = binding.nativeHomepage.root.isVisible
            binding.nativeHomepage.root.visibility = android.view.View.GONE
            binding.webView.visibility = android.view.View.VISIBLE
            binding.btnSummarize.isEnabled = false
            
            // If we are coming directly from the homepage, we want this new URL 
            // to become the "start" of our browsing session for the back button.
            if (wasOnHomePage) {
                binding.webView.clearHistory()
            }
            
            binding.webView.loadUrl(url)
        } else ToastUtils.showError(this, "Invalid URL")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun showConfirmationDialog(title: String, message: String, action: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> action() }.setNegativeButton("No", null).show()
    }

    private fun showExitConfirmationDialog() {
        isExitDialogShowing = true
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Exit App").setMessage("Are you sure you want to exit?")
            .setPositiveButton("Exit") { _, _ -> isExitDialogShowing = false; finishAffinity() }
            .setNegativeButton("Cancel") { _, _ -> isExitDialogShowing = false }
            .setOnCancelListener { isExitDialogShowing = false }.show()
    }
}