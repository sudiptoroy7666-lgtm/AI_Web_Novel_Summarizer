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
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import com.example.novel_summary.utils.hide
import com.example.novel_summary.utils.visible
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

    // ── Dark / Light mode state ──────────────────────────────────
    private var isDarkMode: Boolean = true   // default ON

    companion object {
        private const val HOME_URL = "https://app.home/"
        private const val HOME_DATA_URL = "https://novelsummarizer.home/"
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

        TopMenuHide()
        setupWebView()
        setupDarkModeToggle()          // ← NEW
        setupSearchBar()
        setupNavigationButtons()
        setupSummarizeButton()
        setupBookmarkButton()
        setupHomeButton()
        setupRefreshButton()
        setupBackPressedCallback()

        updateDarkModeToggleIcon()

        intent.getStringExtra("SELECTED_URL")?.let { url ->
            webViewUrl = url
            loadUrl(url)
        } ?: loadHomePage()
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
        if (webViewUrl != null && !isHomePageUrl(webViewUrl!!)) {
            updateBookmarkState(webViewUrl)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            currentWebViewJob?.cancel()
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────
    // DARK MODE TOGGLE
    // ─────────────────────────────────────────────────────────────

    /**
     * Wire up the toggle button (id: btnDarkModeToggle in your XML).
     * Place it on the RIGHT side of your top bar.
     */
    private fun setupDarkModeToggle() {
        binding.btnDarkModeToggle.setOnClickListener {
            isDarkMode = !isDarkMode
            prefs.edit().putBoolean(PREF_DARK_MODE, isDarkMode).apply()
            updateDarkModeToggleIcon()
            applyCurrentTheme()
            val msg = if (isDarkMode) "Dark mode ON" else "Light mode ON"
            ToastUtils.showShort(this, msg)
        }
    }

    private fun updateDarkModeToggleIcon() {
        // Use moon for dark, sun for light.
        // Replace with your actual drawable resource names:
        binding.btnDarkModeToggle.setImageResource(
            if (isDarkMode) R.drawable.sunny_24   // e.g. moon icon
            else R.drawable.moon_stars_24              // e.g. sun icon
        )
    }

    private fun applyCurrentTheme() {
        updateWebViewThemeSettings()
        
        val currentUrl = binding.webView.url ?: return
        if (isHomePageUrl(currentUrl)) return  // homepage manages its own style

        if (isDarkMode) {
            WebViewUtils.injectDarkMode(binding.webView)
        } else {
            WebViewUtils.removeDarkMode(binding.webView)
            WebViewUtils.injectAdBlockOnly(binding.webView)
        }
    }

    private fun updateWebViewThemeSettings() {
        if (isDarkMode) {
            binding.webView.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
            WebViewUtils.setDarkModeConfig(binding.webView, true)
        } else {
            binding.webView.setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
            WebViewUtils.setDarkModeConfig(binding.webView, false)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // WEBVIEW SETUP
    // ─────────────────────────────────────────────────────────────

    private fun TopMenuHide() {
        binding.topmenuhide.setOnClickListener {
            binding.topBarContainer.isVisible = !binding.topBarContainer.isVisible
        }
    }

    private fun setupWebView() {
        WebViewUtils.configureWebView(binding.webView)
        updateWebViewThemeSettings()

        binding.webView.webViewClient = object : WebViewClient() {

            // ── REQUEST-LEVEL AD BLOCKING ────────────────────────
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                if (WebViewUtils.shouldBlockRequest(url)) {
                    // Return empty 200 to silently drop the request
                    return WebResourceResponse(
                        "text/plain", "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            // ── PAGE STARTED ─────────────────────────────────────
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                val isHome = isHomePageUrl(url)

                // Instantly dark background on non-home pages, before content paints
                if (!isHome) {
                    val css = if (isDarkMode) "html, body { background-color: #121212 !important; color: #e0e0e0 !important; }"
                              else "html, body { background-color: #ffffff !important; color: #121212 !important; }"
                    view?.evaluateJavascript("""
                        (function(){
                            var s = document.createElement('style');
                            s.innerHTML = '$css';
                            document.head ? document.head.appendChild(s) : document.documentElement.appendChild(s);
                        })();
                    """.trimIndent()) {}
                }

                isLoading = true
                binding.progressBar.isVisible = true
                binding.progressBar.progress = 0
                updateBookmarkState(url)
                updateNavigationButtons()
                binding.btnSummarize.isEnabled = false
            }

            // ── PAGE FINISHED ────────────────────────────────────
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                val isHome = isHomePageUrl(url)
                binding.btnSummarize.isVisible = !isHome
                binding.btnBookmark.isVisible = !isHome

                isLoading = false
                binding.progressBar.isVisible = false
                binding.progressBar.progress = 100
                webViewUrl = url
                webViewTitle = view?.title

                if (!isHome && view != null) {
                    // ① Apply dark/light + CSS ad blocking
                    if (isDarkMode) {
                        WebViewUtils.injectDarkMode(view)
                    } else {
                        WebViewUtils.injectAdBlockOnly(view)
                    }

                    // ② Add padding so content isn't hidden behind UI chrome
                    injectContentPadding(view)
                }

                if (isHome) {
                    updateNavigationButtons()
                    return
                }

                // Save history
                currentWebViewJob?.cancel()
                currentWebViewJob = CoroutineScope(Dispatchers.Main).launch {
                    view?.evaluateJavascript("(function(){return document.title;})()") { title ->
                        val pageTitle = if (title == "\"\"" || title == "null")
                            UrlUtils.extractTitleFromUrl(url ?: "")
                        else title.trim('"')

                        if (url != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                viewModel.saveToHistory(History(url = url, title = pageTitle))
                            }
                        }
                    }
                }

                runOnUiThread {
                    binding.searchEditText.setText(if (!isHomePageUrl(url ?: "")) url else "")
                    updateBookmarkState(url)
                    updateNavigationButtons()
                    binding.btnSummarize.isEnabled = url != null && !isHomePageUrl(url)
                }
            }

            // ── ERRORS ───────────────────────────────────────────
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
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

            // ── EXTERNAL LINKS ───────────────────────────────────
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        ToastUtils.showError(this@MainActivity, "Cannot open this link")
                    }
                    return true
                }
                return false
            }
        }

        // WebChromeClient for progress bar
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

    // ─────────────────────────────────────────────────────────────
    // CONTENT PADDING (keeps text from hiding behind toolbar/nav)
    // ─────────────────────────────────────────────────────────────

    private fun injectContentPadding(view: WebView) {
        val topPx = 50
        val botPx = 40
        view.evaluateJavascript("""
            (function(){
                document.body.style.paddingTop='${topPx}px';
                document.body.style.paddingBottom='${botPx}px';
                document.body.style.boxSizing='border-box';
                document.querySelectorAll('header,nav,.header,.navbar,.top-bar').forEach(function(h){
                    if(window.getComputedStyle(h).position==='fixed') h.style.top='${topPx}px';
                });
                document.querySelectorAll('footer,.footer,.bottom-bar').forEach(function(f){
                    if(window.getComputedStyle(f).position==='fixed') f.style.bottom='${botPx}px';
                });
            })();
        """.trimIndent()) {}
    }

    // ─────────────────────────────────────────────────────────────
    // REFRESH BUTTON
    // ─────────────────────────────────────────────────────────────

    private fun setupRefreshButton() {
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
            ToastUtils.showShort(this, "Refreshing…")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SEARCH BAR
    // ─────────────────────────────────────────────────────────────

    private fun setupSearchBar() {
        binding.searchEditText.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val input = v.text.toString().trim()
                if (input.isNotEmpty()) {
                    loadUrl(UrlUtils.normalizeUrl(input))
                    hideKeyboard()
                    return@setOnEditorActionListener true
                }
                false
            } else false
        }
        binding.btnGo.setOnClickListener {
            val input = binding.searchEditText.text.toString().trim()
            if (input.isNotEmpty()) {
                loadUrl(UrlUtils.normalizeUrl(input))
                hideKeyboard()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // NAVIGATION BUTTONS
    // ─────────────────────────────────────────────────────────────

    private fun setupNavigationButtons() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_backk -> {
                    if (binding.webView.canGoBack()) binding.webView.goBack()
                    else ToastUtils.showShort(this, "No previous page")
                    true
                }
                R.id.menu_forwardd -> {
                    if (binding.webView.canGoForward()) binding.webView.goForward()
                    else ToastUtils.showShort(this, "No forward page")
                    true
                }
                R.id.menu_bookmarks -> {
                    startActivity(Intent(this, Activity_Boolmarks::class.java)); true
                }
                R.id.menu_history -> {
                    startActivity(Intent(this, Activity_History::class.java)); true
                }
                R.id.menu_library -> {
                    startActivity(Intent(this, Activity_Library::class.java)); true
                }
                else -> false
            }
        }
    }

    private fun updateNavigationButtons() {
        val menu = binding.bottomNavigationView.menu
        menu.findItem(R.id.menu_backk)?.setIcon(R.drawable.outline_arrow_back_ios_24)
        menu.findItem(R.id.menu_forwardd)?.setIcon(R.drawable.outline_arrow_forward_ios_24)
        binding.btnRefresh.isEnabled = !isLoading
        binding.btnRefresh.alpha = if (!isLoading) 1.0f else 0.5f
    }

    // ─────────────────────────────────────────────────────────────
    // BOOKMARK BUTTON
    // ─────────────────────────────────────────────────────────────

    private fun setupBookmarkButton() {
        binding.btnBookmark.setOnClickListener {
            val currentUrl = binding.webView.url
            if (currentUrl == null || isHomePageUrl(currentUrl)) {
                ToastUtils.showShort(this, "Cannot bookmark home page")
                return@setOnClickListener
            }
            val currentTitle = binding.webView.title ?: UrlUtils.extractTitleFromUrl(currentUrl)

            if (isBookmarked) {
                showConfirmationDialog("Remove Bookmark", "Remove this bookmark?") {
                    CoroutineScope(Dispatchers.IO).launch {
                        viewModel.removeBookmark(currentUrl)
                        runOnUiThread {
                            isBookmarked = false
                            binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_outline)
                            ToastUtils.showSuccess(this@MainActivity, "Bookmark removed")
                        }
                    }
                }
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    viewModel.saveBookmark(Bookmark(url = currentUrl, title = currentTitle))
                    runOnUiThread {
                        isBookmarked = true
                        binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                        ToastUtils.showSuccess(this@MainActivity, "Bookmarked!")
                    }
                }
            }
        }
    }

    private fun updateBookmarkState(url: String?) {
        if (url == null || isHomePageUrl(url)) {
            binding.btnBookmark.isVisible = false
            isBookmarked = false
            return
        }
        binding.btnBookmark.isVisible = true
        CoroutineScope(Dispatchers.IO).launch {
            val bookmark = viewModel.getBookmarkByUrl(url)
            runOnUiThread {
                isBookmarked = bookmark != null
                binding.btnBookmark.setImageResource(
                    if (isBookmarked) R.drawable.ic_bookmark_filled
                    else R.drawable.ic_bookmark_outline
                )
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
    // SUMMARIZE BUTTON
    // ─────────────────────────────────────────────────────────────

    private fun setupSummarizeButton() {
        binding.btnSummarize.setOnClickListener {
            val currentUrl = binding.webView.url
            if (currentUrl != null && isHomePageUrl(currentUrl)) {
                ToastUtils.showShort(this, "Cannot summarize home page")
                return@setOnClickListener
            }
            if (isLoading) {
                ToastUtils.showShort(this, "Please wait for page to finish loading")
                return@setOnClickListener
            }
            val url = currentUrl ?: return@setOnClickListener

            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Extracting Content")
                .setMessage("Extracting text from page…")
                .setCancelable(false)
                .create()
            dialog.show()

            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(1000)
                binding.webView.evaluateJavascript(WebViewUtils.extractCleanText(binding.webView)) { content ->
                    dialog.dismiss()
                    if (content != "\"\"" && content != "null" && content.length > 100) {
                        ContentHolder.setContent(
                            content = content.trim('"'),
                            url = url,
                            title = binding.webView.title ?: "Untitled"
                        )
                        startActivity(Intent(this@MainActivity, ActivitySummary::class.java))
                    } else {
                        runOnUiThread { showContentExtractionOptions(url) }
                    }
                }
            }
        }
    }

    private fun showContentExtractionOptions(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Content Extraction Failed")
            .setMessage("Could not automatically extract content. Try:")
            .setItems(arrayOf("Retry Extraction", "Select Content Manually", "View Page Source")) { _, which ->
                when (which) {
                    0 -> retryContentExtraction(url)
                    1 -> showManualSelectionDialog()
                    2 -> showPageSource()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun retryContentExtraction(url: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Retrying…").setMessage("Waiting for content…").setCancelable(false).create()
        dialog.show()
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(3000)
            binding.webView.evaluateJavascript(WebViewUtils.extractCleanText(binding.webView)) { content ->
                dialog.dismiss()
                if (content != "\"\"" && content != "null" && content.length > 100) {
                    ContentHolder.setContent(content.trim('"'), url, binding.webView.title ?: "Untitled")
                    startActivity(Intent(this@MainActivity, ActivitySummary::class.java))
                } else {
                    ToastUtils.showError(this@MainActivity, "Still could not extract. Try manual selection.")
                }
            }
        }
    }

    private fun showManualSelectionDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Manual Content Selection")
            .setMessage("Enter CSS selector (e.g., '.chapter-content'):")
            .setView(androidx.appcompat.widget.AppCompatEditText(this).apply {
                hint = "CSS selector"
                id = android.R.id.edit
            })
            .setPositiveButton("Extract") { dlg, _ ->
                val sel = (dlg as AlertDialog).findViewById<androidx.appcompat.widget.AppCompatEditText>(android.R.id.edit)?.text.toString().trim()
                if (sel.isNotEmpty()) extractWithCustomSelector(sel)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractWithCustomSelector(selector: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Extracting…").setMessage("Using: $selector").setCancelable(false).create()
        dialog.show()
        val js = """
            (function(){
                try {
                    var el = document.querySelector('$selector');
                    return el ? el.innerText.trim() : 'NOT_FOUND';
                } catch(e) { return 'ERROR: '+e.message; }
            })()
        """.trimIndent()
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(500)
            binding.webView.evaluateJavascript(js) { result ->
                dialog.dismiss()
                val clean = result.trim('"')
                if (clean == "NOT_FOUND" || clean.startsWith("ERROR:") || clean.length < 100) {
                    ToastUtils.showError(this@MainActivity, "Selector didn't work. Try another.")
                } else {
                    ContentHolder.setContent(clean, binding.webView.url ?: "", binding.webView.title ?: "Untitled")
                    startActivity(Intent(this@MainActivity, ActivitySummary::class.java))
                }
            }
        }
    }

    private fun showPageSource() {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Loading source…").setCancelable(true).create()
        dlg.show()
        binding.webView.evaluateJavascript("(function(){return document.body.innerHTML.substring(0,2000);})()") { html ->
            dlg.dismiss()
            AlertDialog.Builder(this)
                .setTitle("HTML Structure (first 2000 chars)")
                .setMessage(html.trim('"'))
                .setPositiveButton("OK", null).show()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // BACK PRESS
    // ─────────────────────────────────────────────────────────────

    private fun setupBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentUrl = binding.webView.url
                val onHome = currentUrl != null && isHomePageUrl(currentUrl)
                when {
                    onHome && !isExitDialogShowing -> showExitConfirmationDialog()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> loadHomePage()
                }
            }
        })
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun isHomePageUrl(url: String?): Boolean =
        url == HOME_URL || url == "about:blank" ||
                url?.startsWith(HOME_DATA_URL) == true ||
                url?.contains("novelsummarizer.home") == true

    private fun loadUrl(url: String) {
        if (UrlUtils.isValidUrl(url)) {
            binding.btnSummarize.isEnabled = false
            binding.webView.loadUrl(url)
        } else {
            ToastUtils.showError(this, "Invalid URL")
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun showConfirmationDialog(title: String, message: String, action: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("No", null).show()
    }

    private fun showExitConfirmationDialog() {
        isExitDialogShowing = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exit App").setMessage("Are you sure you want to exit?")
            .setPositiveButton("Exit") { _, _ -> isExitDialogShowing = false; finishAffinity() }
            .setNegativeButton("Cancel") { _, _ -> isExitDialogShowing = false }
            .setOnCancelListener { isExitDialogShowing = false }
            .show()
    }

    // ─────────────────────────────────────────────────────────────
    // HOME PAGE (unchanged HTML content)
    // ─────────────────────────────────────────────────────────────

    private fun loadHomePage() {
        binding.searchEditText.setText("")
        binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_outline)
        isBookmarked = false

        val htmlContent = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>NovelSummarizer</title>
            <style>
                * { margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent; }
                :root {
                    --primary:#06B6D4;--secondary:#0891B2;--accent:#22D3EE;
                    --dark:#0C4A6E;--light:#FFFFFF;--gray:#67E8F9;
                    --card-shadow:0 8px 20px rgba(6,182,212,0.18);
                    --transition:all 0.3s cubic-bezier(0.25,0.8,0.25,1);
                }
                body {
                    font-family:'Segoe UI',system-ui,-apple-system,sans-serif;
                    background:linear-gradient(135deg,#0C4A6E 0%,#075985 40%,#0369A1 100%);
                    color:var(--light);min-height:100vh;
                    padding:16px;padding-bottom:30px;overflow-x:hidden;
                    position:relative;
                }
                body::before {
                    content:'';position:fixed;top:0;left:0;right:0;bottom:0;
                    background:url("data:image/svg+xml,%3Csvg viewBox='0 0 400 400' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)'/%3E%3C/svg%3E");
                    opacity:0.03;pointer-events:none;z-index:0;
                }
                .container{max-width:100%;margin:0 auto;position:relative;z-index:1;}
                .header{text-align:center;padding:24px 16px 16px;margin-bottom:24px;}
                .app-icon {
            width:72px;height:72px;
            background:linear-gradient(145deg,#1E293B,#334155);
            border-radius:24px;display:flex;align-items:center;justify-content:center;
            margin:0 auto 16px;box-shadow:0 6px 24px rgba(59,130,246,0.4);
            animation:float 3s ease-in-out infinite;
            overflow:hidden;border:1px solid rgba(59,130,246,0.3);
        }
        .app-icon span{display:none;}
        h1 {
            font-size:28px;font-weight:800;
            background:linear-gradient(to right,#FFFFFF,#60A5FA);
            -webkit-background-clip:text;background-clip:text;
            color:transparent;margin-bottom:8px;letter-spacing:-0.5px;
        }
                .subtitle{color:#A5F3FC;font-size:16px;line-height:1.5;max-width:90%;margin:0 auto;}
                .search-container {
                    background:rgba(12,74,110,0.6);border-radius:20px;padding:16px;
                    box-shadow:var(--card-shadow);margin-bottom:28px;
                    border:1px solid rgba(6,182,212,0.25);
                    backdrop-filter:blur(12px);
                }
                .search-title{display:flex;align-items:center;margin-bottom:14px;color:#22D3EE;font-weight:600;font-size:15px;}
                .search-title i{margin-right:8px;font-size:18px;}
                .search-box{display:flex;background:rgba(255,255,255,0.95);border-radius:16px;overflow:hidden;border:1px solid rgba(6,182,212,0.3);}
                #searchInput{flex:1;background:transparent;border:none;color:#0C4A6E;padding:14px 16px;font-size:16px;outline:none;caret-color:var(--primary);}
                #searchInput::placeholder{color:#0891B2;opacity:0.7;}
                .search-btn{background:linear-gradient(135deg,#06B6D4,#0891B2);color:white;border:none;width:56px;display:flex;align-items:center;justify-content:center;font-size:18px;cursor:pointer;}
                .search-btn:hover{background:linear-gradient(135deg,#22D3EE,#06B6D4);}
                .section-title{display:flex;align-items:center;justify-content:space-between;margin:28px 0 16px;font-size:20px;font-weight:700;}
                .section-title span{background:linear-gradient(to right,#FFFFFF,#22D3EE);-webkit-background-clip:text;background-clip:text;color:transparent;}
                .quick-actions{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:28px;}
                .action-card{background:rgba(255,255,255,0.1);border-radius:18px;padding:18px 12px;text-align:center;transition:var(--transition);border:1px solid rgba(6,182,212,0.2);cursor:pointer;backdrop-filter:blur(8px);}
                .action-card:active{transform:scale(0.97);background:rgba(255,255,255,0.18);}
                .action-icon{width:56px;height:56px;background:linear-gradient(135deg,rgba(6,182,212,0.3),rgba(8,145,178,0.15));border-radius:16px;display:flex;align-items:center;justify-content:center;margin:0 auto 12px;font-size:24px;border:1px solid rgba(6,182,212,0.25);}
                .action-title{font-weight:600;font-size:15px;margin-bottom:4px;color:#FFFFFF;}
                .action-desc{color:#A5F3FC;font-size:13px;line-height:1.4;}
                .sites-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:16px;}
                .site-card{background:rgba(255,255,255,0.1);border-radius:18px;padding:18px;transition:var(--transition);border:1px solid rgba(6,182,212,0.18);cursor:pointer;position:relative;overflow:hidden;backdrop-filter:blur(8px);}
                .site-card:active{transform:scale(0.98);background:rgba(255,255,255,0.16);}
                .site-card::before{content:'';position:absolute;top:0;left:0;right:0;height:2px;background:linear-gradient(90deg,transparent,rgba(34,211,238,0.8),transparent);opacity:0;transition:opacity 0.3s;}
                .site-card:active::before{opacity:1;}
                .site-name{font-weight:700;font-size:17px;margin-bottom:6px;display:flex;align-items:center;color:#FFFFFF;}
                .site-name i{margin-right:10px;color:#22D3EE;font-size:20px;}
                .site-desc{color:#A5F3FC;font-size:14px;line-height:1.5;}
                .footer{text-align:center;margin-top:30px;color:#67E8F9;font-size:14px;padding:20px;border-top:1px solid rgba(6,182,212,0.2);}
                .highlight{color:#FFFFFF;font-weight:600;}
                @keyframes float{0%{transform:translateY(0)}50%{transform:translateY(-6px)}100%{transform:translateY(0)}}
                @media(min-width:480px){.sites-grid{grid-template-columns:repeat(3,1fr);}}
                @media(min-width:768px){body{padding:24px;}.container{max-width:720px;}h1{font-size:36px;}.quick-actions{grid-template-columns:repeat(4,1fr);}}
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <div class="app-icon">
                <svg viewBox="0 0 108 108" xmlns="http://www.w3.org/2000/svg" width="72" height="72">
                    <circle cx="54" cy="54" r="54" fill="#1E293B"/>
                    <circle cx="54" cy="54" r="46" fill="#334155"/>
                    <path d="M22,35 C22,33.3 23.3,32 25,32 L51,32 L51,76 L25,76 C23.3,76 22,74.7 22,73 Z" fill="#F8FAFC"/>
                    <path d="M57,32 L83,32 C84.7,32 86,33.3 86,35 L86,73 C86,74.7 84.7,76 83,76 L57,76 Z" fill="#E2E8F0"/>
                    <rect x="51" y="30" width="6" height="48" fill="#3B82F6"/>
                    <rect x="22" y="32" width="29" height="3" fill="#1D4ED8" opacity="0.3"/>
                    <rect x="57" y="32" width="29" height="3" fill="#1D4ED8" opacity="0.3"/>
                    <circle cx="36" cy="54" r="5" fill="#3B82F6"/>
                    <circle cx="27" cy="43" r="3.5" fill="#60A5FA"/>
                    <circle cx="27" cy="65" r="3.5" fill="#60A5FA"/>
                    <circle cx="44" cy="42" r="3" fill="#93C5FD"/>
                    <circle cx="44" cy="66" r="3" fill="#93C5FD"/>
                    <line x1="27" y1="43" x2="31" y2="54" stroke="#3B82F6" stroke-width="1.2"/>
                    <line x1="27" y1="65" x2="31" y2="54" stroke="#3B82F6" stroke-width="1.2"/>
                    <line x1="41" y1="54" x2="44" y2="42" stroke="#3B82F6" stroke-width="1.2"/>
                    <line x1="41" y1="54" x2="44" y2="66" stroke="#3B82F6" stroke-width="1.2"/>
                    <line x1="27" y1="43" x2="44" y2="42" stroke="#60A5FA" stroke-width="0.8"/>
                    <line x1="27" y1="65" x2="44" y2="66" stroke="#60A5FA" stroke-width="0.8"/>
                    <rect x="62" y="42" width="18" height="2" fill="#CBD5E1"/>
                    <rect x="62" y="48" width="18" height="2" fill="#CBD5E1"/>
                    <rect x="62" y="54" width="14" height="2" fill="#CBD5E1"/>
                    <rect x="62" y="60" width="18" height="2" fill="#CBD5E1"/>
                    <rect x="62" y="66" width="11" height="2" fill="#CBD5E1"/>
                    <polygon points="78,60 79.2,63.6 83,63.6 80,65.8 81.2,69.4 78,67.2 74.8,69.4 76,65.8 73,63.6 76.8,63.6" fill="#3B82F6"/>
                    <circle cx="54" cy="26" r="2" fill="#93C5FD"/>
                    <circle cx="46" cy="23" r="1.5" fill="#3B82F6"/>
                    <circle cx="62" cy="23" r="1.5" fill="#60A5FA"/>
                </svg>
            </div>
                    <h1>NovelSummarizer</h1>
                    <p class="subtitle">AI-powered summaries for web novels • Read faster • Never lose your place</p>
                </div>
                <div class="search-container">
                    <div class="search-title"><i>🔍</i> Search or Enter URL</div>
                    <div class="search-box">
                        <input type="text" id="searchInput" placeholder="Search novels or enter URL…" autocapitalize="off" autocomplete="off">
                        <button class="search-btn" onclick="handleSearch()">➤</button>
                    </div>
                </div>
                <div class="section-title"><span>⚡ Quick Actions</span></div>
                <div class="quick-actions">
                    <div class="action-card" onclick="Android.openBookmarks()"><div class="action-icon">⭐</div><div class="action-title">Bookmarks</div><div class="action-desc">Your saved pages</div></div>
                    <div class="action-card" onclick="Android.openHistory()"><div class="action-icon">🕒</div><div class="action-title">History</div><div class="action-desc">Recent browsing</div></div>
                    <div class="action-card" onclick="Android.openLibrary()"><div class="action-icon">📚</div><div class="action-title">Library</div><div class="action-desc">Saved summaries</div></div>
                </div>
                <div class="section-title"><span>🌐 Popular Novel Sites</span></div>
                <div class="sites-grid">
                    <div class="site-card" onclick="Android.openUrl('https://www.webnovel.com')"><div class="site-name"><i>📖</i> WebNovel</div><div class="site-desc">Official translations & originals</div></div>
                    <div class="site-card" onclick="Android.openUrl('https://www.wuxiaworld.com')"><div class="site-name"><i>⚔️</i> WuxiaWorld</div><div class="site-desc">Chinese fantasy novels</div></div>
                    <div class="site-card" onclick="Android.openUrl('https://fictionme.net/')"><div class="site-name"><i>🏰</i> Fiction Me</div><div class="site-desc">Web serials & fiction</div></div>
                    <div class="site-card" onclick="Android.openUrl('https://www.novelupdates.com')"><div class="site-name"><i>🔍</i> Novel Updates</div><div class="site-desc">Novel directory & reviews</div></div>
                    <div class="site-card" onclick="Android.openUrl('https://www.royalroad.com/home')"><div class="site-name"><i>📦</i> Royal Road</div><div class="site-desc">Fantasy web novels</div></div>
                    <div class="site-card" onclick="Android.openUrl('https://www.novelnow.com/')"><div class="site-name"><i>✨</i> Novel Now</div><div class="site-desc">Novels & audio books</div></div>
                </div>
                <div class="footer">
                    <p>Tap any site to start reading • Summarize with <span class="highlight">✨ button</span> while browsing</p>
                </div>
            </div>
            <script>
                function handleSearch() {
                    var input = document.getElementById('searchInput').value.trim();
                    if (input) Android.search(input);
                }
                document.getElementById('searchInput').addEventListener('keypress', function(e) {
                    if (e.key === 'Enter') handleSearch();
                });
                window.onload = function() {
                    setTimeout(function(){ document.getElementById('searchInput').focus(); }, 300);
                };
            </script>
        </body>
        </html>

        """.trimIndent()

        binding.webView.loadDataWithBaseURL(
            HOME_DATA_URL, htmlContent, "text/html", "UTF-8", null
        )

        binding.webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun search(query: String) = runOnUiThread { loadUrl(UrlUtils.normalizeUrl(query)) }
            @android.webkit.JavascriptInterface
            fun openUrl(url: String) = runOnUiThread { loadUrl(url) }
            @android.webkit.JavascriptInterface
            fun openBookmarks() = runOnUiThread { startActivity(Intent(this@MainActivity, Activity_Boolmarks::class.java)) }
            @android.webkit.JavascriptInterface
            fun openHistory() = runOnUiThread { startActivity(Intent(this@MainActivity, Activity_History::class.java)) }
            @android.webkit.JavascriptInterface
            fun openLibrary() = runOnUiThread { startActivity(Intent(this@MainActivity, Activity_Library::class.java)) }
        }, "Android")
    }
}