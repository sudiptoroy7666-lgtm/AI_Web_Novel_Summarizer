// utils/WebViewUtils.kt - REPLACE ENTIRE FILE
package com.example.novel_summary.utils

import android.webkit.WebSettings
import android.webkit.WebView

object WebViewUtils {

    // ─────────────────────────────────────────────
    // AD BLOCKING — DOMAIN LIST (expanded)
    // ─────────────────────────────────────────────
    private val AD_DOMAINS = setOf(
        // Google ad network
        "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "adservice.google.co", "ads.youtube.com", "googleadservices.com",
        "googletagmanager.com", "googletagservices.com", "google-analytics.com",
        "analytics.google.com", "stats.g.doubleclick.net",
        // Major ad networks
        "admob.com", "taboola.com", "outbrain.com", "revcontent.com",
        "mgid.com", "infolinks.com", "popads.net", "popcash.net",
        "propellerads.com", "exoclick.com", "adsterra.com",
        "onclickads.net", "adnow.com", "advertising.com",
        "amazon-adsystem.com", "bidvertiser.com", "buysellads.com",
        "chitika.com", "media.net", "adcolony.com", "applovin.com",
        "chartboost.com", "unityads.unity3d.com", "vungle.com",
        // Programmatic / header bidding
        "adnxs.com", "rubiconproject.com", "openx.net", "pubmatic.com",
        "casalemedia.com", "indexexchange.com", "servedbyopenx.com",
        "appnexus.com", "sovrn.com", "lijit.com", "sharethrough.com",
        "criteo.com", "criteo.net", "smartadserver.com", "33across.com",
        "yieldmo.com", "triplelift.com", "spotxchange.com", "spotx.tv",
        "adform.net", "adition.com", "tradedoubler.com",
        // Trackers & analytics used for ad targeting
        "scorecardresearch.com", "quantserve.com", "comscore.com",
        "hotjar.com", "mouseflow.com", "clicktale.net",
        "optimizely.com", "mixpanel.com", "amplitude.com",
        "segment.io", "segment.com", "fullstory.com",
        // Pop / push ad networks
        "hilltopads.net", "adskeeper.co.uk", "adtelligent.com",
        "yllix.com", "traffichunt.com", "adhitz.com",
        "clksite.com", "cpalead.com", "cpaway.com",
        // Social trackers
        "connect.facebook.net", "platform.twitter.com",
        "ads.twitter.com", "t.co", "pixel.facebook.com",
        // Misc spam/malware
        "zedo.com", "undertone.com", "conversantmedia.com",
        "adtech.de", "yieldmanager.com", "yieldmanager.net",
        "advertising.com", "valueclick.com", "valueclick.net"
    )

    // URL path/keyword patterns to block
    private val AD_URL_KEYWORDS = listOf(
        "/ads/", "/ad/", "/adv/", "/advertisement/",
        "/pagead/", "/banner/", "/banners/",
        "/popup/", "/popups/", "/interstitial/",
        "/tracking/", "/tracker/", "/pixel/",
        "/analytics/", "/collect/", "/beacon/",
        "/sponsored/", "/promo/", "/promotions/",
        "adsbygoogle", "googletag", "gpt.js",
        "prebid.js", "prebid.min.js",
        "ads.js", "ad.js", "banner.js",
        "/track?", "/click?", "/imp?", "/view?bid"
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

        // Security
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSafeBrowsingEnabled(true)
        @Suppress("DEPRECATION")
        settings.setAllowFileAccess(false)
        @Suppress("DEPRECATION")
        settings.setAllowContentAccess(false)

        // UX
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        // ✅ ADD THIS — applies dark before any content paints (no flash)
        enableBuiltInDarkMode(webView)
    }

    private fun enableBuiltInDarkMode(webView: WebView) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Works on API 29+ without any extra dependency
                @Suppress("DEPRECATION")
                webView.settings.forceDark = android.webkit.WebSettings.FORCE_DARK_ON
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

        // Never block our own home page or about:blank
        if (lower.contains("novelsummarizer.home") ||
            lower == "about:blank") return false

        // Block by domain
        for (domain in AD_DOMAINS) {
            if (lower.contains(domain)) return true
        }

        // Block by URL pattern
        for (keyword in AD_URL_KEYWORDS) {
            if (lower.contains(keyword)) return true
        }

        return false
    }

    // ─────────────────────────────────────────────
    // DARK MODE INJECTION
    // ─────────────────────────────────────────────

    /**
     * Injects dark mode CSS + DOM ad removal into any web page.
     * Call this ONLY for non-home pages.
     */
    fun injectDarkMode(webView: WebView) {
        val script = """
        (function() {
            // ── DARK MODE ──────────────────────────────────────────
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

            // ── CSS AD REMOVAL ─────────────────────────────────────
            if (!document.getElementById('__adBlockStyle__')) {
                var a = document.createElement('style');
                a.id = '__adBlockStyle__';
                a.innerHTML = `
                    /* Ad class/id patterns */
                    .ads, .ad, .advertisement, .ad-banner, .ad-container,
                    .adsbygoogle, .google-ads, .ad-unit, .sponsored,
                    .taboola, .outbrain, .revcontent, .mgid,
                    .advertising, .ad-slot, .ad-wrapper, .ad-box,
                    .ad-header, .ad-footer, .ad-sidebar, .ad-inline,
                    .native-ad, .native-ad-container, .promoted,
                    .ad-label, .ad-text, .ad-link, .ad-image,
                    .popup, .modal-ad, .interstitial-ad,
                    .newsletter-popup, .subscribe-popup,
                    .cookie-banner, .cookie-notice, .cookie-bar,
                    .floating-ad, .sticky-ad, .fixed-ad,
                    .video-ad, .pre-roll, .mid-roll,
                    .sidebar-ad, .footer-ad, .header-ad,
                    .leaderboard, .billboard, .skyscraper,
                    .banner-ad, .rectangle-ad, .square-ad,
                    .overlay-ad, .lightbox-ad,
                    [class*="advert"], [class*="Advert"],
                    [class*="banner"][class*="ad"],
                    [id*="google_ads"], [id*="div-gpt-ad"],
                    [id*="taboola"], [id*="outbrain"],
                    ins.adsbygoogle,
                    /* iframes from ad networks */
                    iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                    iframe[src*="googleads"], iframe[src*="taboola"],
                    iframe[src*="outbrain"], iframe[src*="revcontent"],
                    iframe[src*="adsterra"], iframe[src*="exoclick"],
                    iframe[src*="propellerads"],
                    /* Push notification bars */
                    .push-notification-bar, .push-notification-wrapper,
                    .notification-bar, .top-notification {
                        display: none !important;
                        visibility: hidden !important;
                        opacity: 0 !important;
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

            // ── DOM AD REMOVAL ─────────────────────────────────────
            var adSelectors = [
                'ins.adsbygoogle', '[id^="div-gpt-ad"]',
                '[data-ad-slot]', '[data-ad-format]',
                '.taboola-widget', '.outbrain-widget',
                '#taboola-below-article-thumbnails',
                '#taboola-right-sidebar',
                '.mgid-container', '.revcontent-widget',
                '.adsbygoogle', '[aria-label="Advertisement"]',
                '.ZEqje', '.u_yzgD'  // common Taboola wrappers
            ];
            adSelectors.forEach(function(sel) {
                try {
                    document.querySelectorAll(sel).forEach(function(el) {
                        el.parentNode && el.parentNode.removeChild(el);
                    });
                } catch(e) {}
            });

            // Block popups / new window opens
            window.open = function() { return null; };
        })();
        """.trimIndent()

        webView.evaluateJavascript(script) {}
    }

    /**
     * Removes dark mode and restores light/original appearance.
     * Call this when switching to light mode.
     */
    fun removeDarkMode(webView: WebView) {
        val script = """
        (function() {
            // Remove dark mode style tag
            var dark = document.getElementById('__darkModeStyle__');
            if (dark) dark.parentNode.removeChild(dark);

            // Reset inline styles
            document.documentElement.style.backgroundColor = '';
            document.body.style.backgroundColor = '';
            document.body.style.color = '';
            document.body.style.paddingTop = '';
            document.body.style.paddingBottom = '';

            // Ad block style stays (we still want ads removed)
        })();
        """.trimIndent()

        webView.evaluateJavascript(script) {}
    }

    /**
     * Injects ad block only (no dark mode). For light mode browsing.
     */
    fun injectAdBlockOnly(webView: WebView) {
        val script = """
        (function() {
            if (document.getElementById('__adBlockStyle__')) return;
            var a = document.createElement('style');
            a.id = '__adBlockStyle__';
            a.innerHTML = `
                .ads,.ad,.advertisement,.ad-banner,.ad-container,
                .adsbygoogle,.google-ads,.ad-unit,.sponsored,
                .taboola,.outbrain,.revcontent,.mgid,
                [id^="div-gpt-ad"], ins.adsbygoogle,
                iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                iframe[src*="googleads"], iframe[src*="taboola"] {
                    display: none !important;
                    height: 0 !important;
                    margin: 0 !important;
                    padding: 0 !important;
                }
            `;
            document.head.appendChild(a);

            // Still block popup windows
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
                // Remove known ad/junk elements first
                var junk = [
                    'script', 'style', 'nav', 'header', 'footer', 'aside', 'iframe',
                    '.ads', '.advertisement', '.ad-banner', '.popup', '.modal',
                    '.social-share', '.comments', '.related-posts', '.footer-links',
                    '[class*="ads"]', '[class*="banner"]', '[class*="popup"]',
                    '#comments', '#disqus_thread', '.disqus',
                    '.taboola', '.outbrain', '.revcontent', '.mgid',
                    'ins.adsbygoogle', '[id^="div-gpt-ad"]'
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