// utils/WebViewUtils.kt - REPLACE ENTIRE FILE
package com.example.novel_summary.utils

import android.webkit.WebSettings
import android.webkit.WebView

object WebViewUtils {

    // ─────────────────────────────────────────────
    // AD BLOCKING — DOMAIN LIST
    // ─────────────────────────────────────────────
    private val AD_DOMAINS = setOf(
        "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "adservice.google.co", "ads.youtube.com", "googleadservices.com",
        "googletagmanager.com", "googletagservices.com", "google-analytics.com",
        "analytics.google.com", "stats.g.doubleclick.net",
        "admob.com", "taboola.com", "outbrain.com", "revcontent.com",
        "mgid.com", "infolinks.com", "popads.net", "popcash.net",
        "propellerads.com", "exoclick.com", "adsterra.com",
        "onclickads.net", "adnow.com",
        "amazon-adsystem.com", "bidvertiser.com", "buysellads.com",
        "chitika.com", "media.net", "adcolony.com", "applovin.com",
        "chartboost.com", "unityads.unity3d.com", "vungle.com",
        "adnxs.com", "rubiconproject.com", "openx.net", "pubmatic.com",
        "casalemedia.com", "indexexchange.com", "servedbyopenx.com",
        "appnexus.com", "sovrn.com", "lijit.com", "sharethrough.com",
        "criteo.com", "criteo.net", "smartadserver.com", "33across.com",
        "yieldmo.com", "triplelift.com", "spotxchange.com", "spotx.tv",
        "adform.net", "adition.com", "tradedoubler.com",
        "scorecardresearch.com", "quantserve.com", "comscore.com",
        "hotjar.com", "mouseflow.com", "clicktale.net",
        "hilltopads.net", "adskeeper.co.uk", "adtelligent.com",
        "yllix.com", "traffichunt.com", "adhitz.com",
        "ads.twitter.com", "pixel.facebook.com",
        "zedo.com", "undertone.com", "conversantmedia.com",
        "adtech.de", "yieldmanager.com", "yieldmanager.net",
        "valueclick.com", "valueclick.net"
    )

    // ✅ SAFE: only specific ad script paths, NOT broad words like /ad/ /banner/
    private val AD_URL_KEYWORDS = listOf(
        "/pagead/", "/adsbygoogle", "googletag.js",
        "gpt.js", "prebid.js", "prebid.min.js"
    )

    // ─────────────────────────────────────────────
    // CONFIGURE WEBVIEW
    // ─────────────────────────────────────────────
    fun configureWebView(webView: WebView) {
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true

        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSafeBrowsingEnabled(true)
        @Suppress("DEPRECATION")
        settings.setAllowFileAccess(false)
        @Suppress("DEPRECATION")
        settings.setAllowContentAccess(false)

        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
    }

    fun setDarkModeConfig(webView: WebView, isDark: Boolean) {
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDark)
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
                    androidx.webkit.WebSettingsCompat.setForceDark(
                        webView.settings,
                        if (isDark) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF
                    )
                } else {
                    @Suppress("DEPRECATION")
                    webView.settings.forceDark = if (isDark) android.webkit.WebSettings.FORCE_DARK_ON else android.webkit.WebSettings.FORCE_DARK_OFF
                }
            }
        } catch (e: Exception) {
            // CSS injection fallback handles it
        }
    }

    // ─────────────────────────────────────────────
    // AD BLOCKING — REQUEST INTERCEPTOR
    // ─────────────────────────────────────────────
    fun shouldBlockRequest(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase()

        if (lower.contains("novelsummarizer.home") || lower == "about:blank") return false

        for (domain in AD_DOMAINS) {
            if (lower.contains(domain)) return true
        }
        for (keyword in AD_URL_KEYWORDS) {
            if (lower.contains(keyword)) return true
        }
        return false
    }

    // ─────────────────────────────────────────────
    // DARK MODE INJECTION
    // ─────────────────────────────────────────────
    fun injectDarkMode(webView: WebView) {
        val script = """
        (function() {
            document.documentElement.style.backgroundColor = '#121212';
            document.body.style.backgroundColor = '#121212';
            document.body.style.color = '#e0e0e0';

            if (!document.getElementById('__darkModeStyle__')) {
                var s = document.createElement('style');
                s.id = '__darkModeStyle__';
                s.innerHTML = `
                    html, body, div, section, article, main, header,
                    footer, p, span, li, ul, ol, blockquote, pre, code {
                        background-color: #121212 !important;
                        color: #e0e0e0 !important;
                    }
                    input, textarea, select {
                        background-color: #1e1e1e !important;
                        color: #ffffff !important;
                        border-color: #444 !important;
                    }
                    button {
                        background-color: #2a2a3a !important;
                        color: #ffffff !important;
                        border-color: #444 !important;
                    }
                    a { color: #9d8cff !important; }
                    a:visited { color: #c5b8ff !important; }
                    table, th, td {
                        background-color: #1a1a2a !important;
                        border-color: #333 !important;
                    }
                    img { filter: brightness(0.88) contrast(1.05); }
                    ::-webkit-scrollbar { background: #1a1a1a; }
                    ::-webkit-scrollbar-thumb { background: #444; border-radius: 4px; }
                `;
                document.head.appendChild(s);
            }

            // ── CSS AD REMOVAL — safe exact class names only ───────
            // ✅ NO wildcard selectors like [class*="ad"] — those break real sites
            if (!document.getElementById('__adBlockStyle__')) {
                var a = document.createElement('style');
                a.id = '__adBlockStyle__';
                a.innerHTML = `
                    ins.adsbygoogle,
                    .adsbygoogle,
                    .taboola,
                    .outbrain,
                    .revcontent,
                    .mgid,
                    .ad-unit,
                    .ad-container,
                    .ad-banner,
                    .ad-slot,
                    .ad-wrapper,
                    .ad-box,
                    .ad-inline,
                    .ad-sidebar,
                    .ad-footer,
                    .ad-header,
                    .native-ad,
                    .native-ad-container,
                    .modal-ad,
                    .interstitial-ad,
                    .newsletter-popup,
                    .subscribe-popup,
                    .cookie-banner,
                    .cookie-bar,
                    .floating-ad,
                    .sticky-ad,
                    .video-ad,
                    .banner-ad,
                    .overlay-ad,
                    [data-ad-slot],
                    [data-ad-format],
                    [id^="div-gpt-ad"],
                    [id^="taboola-"],
                    [id^="outbrain-"],
                    iframe[src*="doubleclick"],
                    iframe[src*="googlesyndication"],
                    iframe[src*="googleads"],
                    iframe[src*="taboola"],
                    iframe[src*="outbrain"],
                    iframe[src*="revcontent"],
                    iframe[src*="adsterra"],
                    iframe[src*="exoclick"],
                    iframe[src*="propellerads"] {
                        display: none !important;
                        visibility: hidden !important;
                        height: 0 !important;
                        width: 0 !important;
                        min-height: 0 !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        border: none !important;
                        pointer-events: none !important;
                    }
                `;
                document.head.appendChild(a);
            }

            // ── DOM AD REMOVAL — safe exact selectors only ─────────
            var adSelectors = [
                'ins.adsbygoogle',
                '.adsbygoogle',
                '[id^="div-gpt-ad"]',
                '[data-ad-slot]',
                '[data-ad-format]',
                '.taboola-widget',
                '.outbrain-widget',
                '#taboola-below-article-thumbnails',
                '#taboola-right-sidebar',
                '.mgid-container',
                '.revcontent-widget',
                '[aria-label="Advertisement"]'
            ];
            adSelectors.forEach(function(sel) {
                try {
                    document.querySelectorAll(sel).forEach(function(el) {
                        el.parentNode && el.parentNode.removeChild(el);
                    });
                } catch(e) {}
            });

            window.open = function() { return null; };
        })();
        """.trimIndent()

        webView.evaluateJavascript(script) {}
    }

    fun removeDarkMode(webView: WebView) {
        val script = """
        (function() {
            var dark = document.getElementById('__darkModeStyle__');
            if (dark) dark.parentNode.removeChild(dark);
            document.documentElement.style.backgroundColor = '';
            document.body.style.backgroundColor = '';
            document.body.style.color = '';
            document.body.style.paddingTop = '';
            document.body.style.paddingBottom = '';
        })();
        """.trimIndent()
        webView.evaluateJavascript(script) {}
    }

    fun injectAdBlockOnly(webView: WebView) {
        val script = """
        (function() {
            if (document.getElementById('__adBlockStyle__')) return;
            var a = document.createElement('style');
            a.id = '__adBlockStyle__';
            a.innerHTML = `
                ins.adsbygoogle, .adsbygoogle, .taboola, .outbrain,
                .ad-unit, .ad-container, .ad-banner, .ad-slot,
                [id^="div-gpt-ad"], [data-ad-slot],
                iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                iframe[src*="googleads"], iframe[src*="taboola"] {
                    display: none !important;
                    height: 0 !important;
                    margin: 0 !important;
                    padding: 0 !important;
                }
            `;
            document.head.appendChild(a);
            window.open = function() { return null; };
        })();
        """.trimIndent()
        webView.evaluateJavascript(script) {}
    }

    // ─────────────────────────────────────────────
    // CONTENT EXTRACTION
    // ─────────────────────────────────────────────
    fun extractCleanText(webView: WebView): String {
        return """
            (function() {
                var junk = [
                    'script', 'style', 'nav', 'iframe',
                    'ins.adsbygoogle', '.adsbygoogle',
                    '[id^="div-gpt-ad"]', '.taboola', '.outbrain',
                    '#comments', '#disqus_thread', '.disqus'
                ];
                junk.forEach(function(sel) {
                    try { document.querySelectorAll(sel).forEach(function(e){ e.remove(); }); } catch(x){}
                });

                var selectors = [
                    '.cha-words', '.chapter-content', '.chapter-entity', '.chapter-body',
                    '.chapter-inner', '#chapter-content', '#chapter-container',
                    '.fr-view', '.portlet-body',
                    '.entry-content', '.post-content', '.text-left',
                    '.desc', '.chapter-desc', '.text-content', '.content',
                    '.novel-content', '.chapter-text', '.text',
                    'article', 'main', '[role="main"]', '.main-content', '#content',
                    '.container', 'body'
                ];

                var best = null, bestLen = 0;
                selectors.forEach(function(sel) {
                    try {
                        var el = document.querySelector(sel);
                        if (el) {
                            var t = el.innerText.trim();
                            if (t.length > bestLen && t.length > 100) { best = el; bestLen = t.length; }
                        }
                    } catch(x) {}
                });

                if (!best || bestLen < 200) {
                    var paras = Array.from(document.querySelectorAll('p')).filter(function(p) {
                        return p.innerText.trim().length >= 40 && p.querySelectorAll('a').length <= 2;
                    });
                    if (paras.length > 0)
                        return paras.map(function(p){ return p.innerText.trim(); }).join('\n\n');
                }

                return best ? best.innerText.trim() : document.body.innerText.trim();
            })()
        """.trimIndent()
    }

    fun extractPageTitle(webView: WebView): String {
        return "(function(){ return document.title || document.querySelector('h1')?.innerText || 'Untitled'; })()"
    }
}