package vaddempudi.gnanesh.syntaxcli.browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Message;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import android.webkit.WebResourceResponse;

public final class SecureWebViewLayer {

    private static final String TAG = "AuraSecure";

    private static final Set<String> ALLOWED_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "file", "content", "about", "data", "blob"
    ));

    private static final Set<String> HARD_BLOCK_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "ads.google.com",
            "pagead2.googlesyndication.com",
            "outbrain.com",
            "taboola.com",
            "popads.net",
            "propellerads.com",
            "onclickads.net",
            "revcontent.com"
    ));

    private static final Set<String> SEARCH_HOSTS = new HashSet<>(Arrays.asList(
            "google.com", "google.co.uk", "google.co.in", "google.de",
            "google.fr", "google.co.jp", "google.com.br", "google.ca",
            "google.com.au", "google.es", "google.it", "google.ru",
            "google.com.mx", "google.co.id", "google.com.tr",
            "bing.com", "duckduckgo.com", "yahoo.com", "yandex.com",
            "yandex.ru", "baidu.com", "search.yahoo.com", "search.brave.com"
    ));

    private static final Set<String> MULTI_TLD_SITES = new HashSet<>(Arrays.asList(
            "moviezwap",
            "moviesflix",
            "mkvcinemas",
            "vegamovies",
            "hdhub4u",
            "9xmovies",
            "tamilmv",
            "isaimini",
            "kuttymovies",
            "movierulz"
    ));

    private SecureWebViewLayer() {}

    public static void install(WebView wv,
                               Activity activity,
                               HostTracker hostTracker,
                               Runnable onPopupBlocked) {
        install(wv, activity, hostTracker, onPopupBlocked, false);
    }

    public static void install(WebView wv,
                               Activity activity,
                               HostTracker hostTracker,
                               Runnable onPopupBlocked,
                               boolean popupsAllowedForThisTab) {

        WebViewClient client = new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null) {
                    String s = uri.getScheme();
                    if (s != null) {
                        s = s.toLowerCase(Locale.US);
                        if (s.equals("blob") || s.equals("data") || s.equals("file")) {
                            // Hand back to the WebView so DownloadListener fires.
                            return false;
                        }
                    }
                }
                return handleNavigation(view, uri,
                        request.hasGesture(), request.isForMainFrame(), hostTracker);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null) {
                    String lower = url.toLowerCase(Locale.US);
                    if (lower.startsWith("blob:") || lower.startsWith("data:")
                            || lower.startsWith("file:")) {
                        return false;
                    }
                }
                return handleNavigation(view, Uri.parse(url),
                        true, true, hostTracker);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    WebResourceResponse neutered =
                            TrackingFilter.maybeNeuter(request.getUrl().toString());
                    if (neutered != null) return neutered;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                WebResourceResponse neutered = TrackingFilter.maybeNeuter(url);
                if (neutered != null) return neutered;
                return super.shouldInterceptRequest(view, url);
            }
        };
        wv.setWebViewClient(client);

        wv.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onCreateWindow(WebView view,
                                          boolean isDialog,
                                          boolean isUserGesture,
                                          Message resultMsg) {
                if (!isUserGesture) {
                    if (resultMsg != null && resultMsg.obj != null) {
                        try {
                            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                            WebView throwaway = new WebView(view.getContext());
                            t.setWebView(throwaway);
                            resultMsg.sendToTarget();
                            throwaway.destroy();
                        } catch (Exception ignored) {}
                    }
                    if (onPopupBlocked != null) onPopupBlocked.run();
                    return true;
                }
                WebView.HitTestResult hit = view.getHitTestResult();
                String data = hit != null ? hit.getExtra() : null;

                if (data != null && isLoadableUrl(data)) {
                    try {
                        Intent newTab = new Intent("vaddempudi.gnanesh.syntaxcli.AURA_OPEN_TAB");
                        newTab.putExtra("url", data);
                        newTab.putExtra("incognito", false);
                        androidx.localbroadcastmanager.content.LocalBroadcastManager
                                .getInstance(view.getContext())
                                .sendBroadcast(newTab);
                    } catch (Throwable t) {
                        Log.w(TAG, "AURA_OPEN_TAB broadcast failed", t);
                    }
                    if (onPopupBlocked != null) onPopupBlocked.run();
                    return true;
                }

                try {
                    final WebView stub = new WebView(view.getContext());
                    stub.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                            String u = req.getUrl().toString();
                            if (isLoadableUrl(u)) view.loadUrl(u);
                            v.destroy();
                            return true;
                        }
                        @SuppressWarnings("deprecation")
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, String u) {
                            if (isLoadableUrl(u)) view.loadUrl(u);
                            v.destroy();
                            return true;
                        }
                    });
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(stub);
                    resultMsg.sendToTarget();
                } catch (Exception e) {
                    Log.w(TAG, "popup capture failed", e);
                }

                if (onPopupBlocked != null) onPopupBlocked.run();
                return true;
            }
        });
    }

    /**
     * Schemes that are safe to hand off to the OS via Intent.ACTION_VIEW.
     * These route to other apps on the device (WhatsApp, PhonePe, UPI apps,
     * email, SMS, dialer, Play Store, Alipay, etc.).
     *
     * Deliberately NOT included: javascript:, file: (handled by WebView),
     * content: (handled by WebView), chrome:, chrome-extension:.
     */
    private static final Set<String> EXTERNAL_SCHEMES = new HashSet<>(Arrays.asList(
            // messaging
            "whatsapp", "wa", "telegram", "tg", "signal", "viber",
            "sms", "mms", "smsto", "mailto",
            // telephony
            "tel", "callto", "voicemail",
            // payments (India + international)
            "upi", "phonepe", "paytmmp", "tez", "gpay", "bhim",
            "paypal", "venmo", "cashme", "revolut",
            "alipays", "alipay", "weixin",
            // maps & navigation
            "geo", "maps", "google.navigation", "waze", "baidumap",
            "iosamap", "qqmap",
            // stores & installs
            "market", "play", "itms-apps", "appstore",
            // social / media
            "instagram", "fb", "facebook", "fb-messenger", "twitter",
            "snapchat", "youtube", "vnd.youtube", "spotify", "music",
            // files & productivity
            "gdrive", "googledrive", "dropbox", "onedrive",
            "googlechrome", "firefox",
            // misc
            "sip", "skype", "zoommtg", "intent"
    ));

    /**
     * Extra hardening: never allow these even if a site tries.
     */
    private static final Set<String> BLOCKED_SCHEMES = new HashSet<>(Arrays.asList(
            "javascript", "vbscript", "file", "content",
            "chrome", "chrome-extension", "chrome-native",
            "jar", "view-source"
    ));

    private static boolean handleNavigation(WebView view,
                                            Uri uri,
                                            boolean hasGesture,
                                            boolean isMainFrame,
                                            HostTracker hostTracker) {
        if (uri == null) return true;

        String scheme = uri.getScheme();
        if (scheme == null) return true;
        scheme = scheme.toLowerCase(Locale.US);

        String url = uri.toString();

        // 1. Never allow these under any circumstances.
        if (BLOCKED_SCHEMES.contains(scheme)) {
            return true;
        }

        // 2. Web + WebView-internal schemes: keep the existing per-scheme logic.
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (AdBlocker.isAd(url)) return true;

            String host = uri.getHost();
            if (host != null) host = host.toLowerCase(Locale.US);
            if (host != null && isHardBlocked(host)) return true;

            if (isMainFrame && host != null) {
                // existing trust rules — unchanged
                if (hostTracker.isSearchEngine() || isKnownSearchHost(host)) {
                    hostTracker.rememberIfMainNavigation(host, true);
                    return false;
                }
                String incomingBase = firstLabelOf(host);
                String currentBase  = hostTracker.getFirstLabel();
                if (incomingBase != null && incomingBase.equals(currentBase)
                        && MULTI_TLD_SITES.contains(incomingBase)) {
                    hostTracker.rememberIfMainNavigation(host, true);
                    return false;
                }
                if (!hasGesture && !hostTracker.isSameOrRelatedHost(host)) {
                    hostTracker.noteBlocked(host);
                    return true;
                }
            }
            if (host != null) hostTracker.rememberIfMainNavigation(host, isMainFrame);
            return false;
        }

        if ("file".equals(scheme) || "content".equals(scheme)
                || "about".equals(scheme) || "data".equals(scheme)
                || "blob".equals(scheme)) {
            return false;
        }

        // 3. intent:// URLs — parse and dispatch. Firefox/Chrome-style.
        if ("intent".equals(scheme)) {
            return dispatchIntentUrl(view.getContext(), url);
        }

        // 4. Any other scheme in the whitelist → hand off to the OS.
        if (EXTERNAL_SCHEMES.contains(scheme)) {
            return dispatchExternalScheme(view.getContext(), uri);
        }

        // 5. Everything else is blocked, but log it so you can extend the
        //    whitelist when you find a legitimate scheme you didn't think of.
        android.util.Log.w("AuraSecure",
                "Blocked unsupported scheme '" + scheme + "' for " + url);
        return true;
    }

    /**
     * Dispatches an Intent.ACTION_VIEW for a non-http scheme.
     * Returns true always — the WebView must not try to load the URL itself.
     */
    private static boolean dispatchExternalScheme(android.content.Context ctx, Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Allow the target app to receive the referrer for play-store style
            // attribution if the scheme supports it.
            ctx.startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            // No app installed to handle this scheme. Show a small toast so
            // the user knows why nothing happened.
            try {
                android.widget.Toast.makeText(ctx,
                        "No app can open " + uri.getScheme(),
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            android.util.Log.w("AuraSecure", "Failed to open " + uri, t);
        }
        return true;
    }

    /**
     * Parses a Firefox-style intent:// URL and dispatches it, honouring the
     * S.browser_fallback_url parameter if no app is found.
     * Format: intent://host/path#Intent;scheme=x;package=y;end
     */
    private static boolean dispatchIntentUrl(android.content.Context ctx, String url) {
        try {
            Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (android.content.ActivityNotFoundException e) {
                // Fallback URL — usually the play store page or the web page.
                String fallback = i.getStringExtra("browser_fallback_url");
                if (fallback != null && !fallback.isEmpty()) {
                    Intent fb = new Intent(Intent.ACTION_VIEW, Uri.parse(fallback));
                    fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(fb);
                } else {
                    String pkg = i.getPackage();
                    if (pkg != null) {
                        try {
                            ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=" + pkg))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("AuraSecure", "Failed to parse intent:// " + url, t);
        }
        return true;
    }

    private static boolean isHardBlocked(String host) {
        String h = host;
        while (h.indexOf('.') >= 0) {
            if (HARD_BLOCK_HOSTS.contains(h)) return true;
            h = h.substring(h.indexOf('.') + 1);
        }
        return HARD_BLOCK_HOSTS.contains(h);
    }

    private static boolean isLoadableUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri u = Uri.parse(url);
            String s = u.getScheme();
            return s != null && ALLOWED_SCHEMES.contains(s.toLowerCase(Locale.US));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isKnownSearchHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.US);
        if (h.contains("google.")) return true;
        if (h.contains("bing.")) return true;
        if (h.contains("duckduckgo.")) return true;
        if (h.contains("yahoo.")) return true;
        if (h.contains("yandex.")) return true;
        if (h.contains("baidu.")) return true;
        if (h.contains("search.brave.")) return true;
        return false;
    }

    private static String firstLabelOf(String host) {
        if (host == null) return null;
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }

    public static final class HostTracker {
        private String primaryHost;
        private String primaryBaseDomain;
        private String lastBlockedHost;
        private long lastBlockedAt;

        public synchronized void rememberIfMainNavigation(String host, boolean isMainFrame) {
            if (!isMainFrame || host == null) return;

            if (primaryHost == null) {
                primaryHost = host;
                primaryBaseDomain = baseDomainOf(host);
                return;
            }

            if (host.equals(primaryHost)
                    || host.endsWith("." + primaryHost)
                    || primaryHost.endsWith("." + host)) {
                return;
            }

            if (baseDomainOf(host).equals(primaryBaseDomain)) return;

            primaryHost = host;
            primaryBaseDomain = baseDomainOf(host);
        }

        public synchronized boolean isSameOrRelatedHost(String host) {
            if (host == null) return false;
            if (primaryHost == null) return true;
            if (host.equals(primaryHost)) return true;
            if (host.endsWith("." + primaryHost)) return true;
            if (primaryHost.endsWith("." + host)) return true;

            String base = baseDomainOf(host);
            return base != null && base.equals(primaryBaseDomain);
        }

        public synchronized boolean isSearchEngine() {
            if (primaryHost == null) return false;
            String base = baseDomainOf(primaryHost);
            if (base == null) return false;
            if (SEARCH_HOSTS.contains(base)) return true;
            String first = firstLabelOf(primaryHost);
            if (first != null && first.startsWith("google")) return true;
            if (primaryHost.contains("google.")) return true;
            if (primaryHost.contains("bing.")) return true;
            if (primaryHost.contains("duckduckgo.")) return true;
            if (primaryHost.contains("yahoo.")) return true;
            if (primaryHost.contains("yandex.")) return true;
            return false;
        }

        public synchronized String getFirstLabel() {
            return firstLabelOf(primaryHost);
        }

        public synchronized void noteBlocked(String host) {
            long now = System.currentTimeMillis();
            if (!host.equals(lastBlockedHost) || now - lastBlockedAt > 3000L) {
                // REMOVED: Log.d — this fired on every ad redirect
                lastBlockedHost = host;
                lastBlockedAt = now;
            }
        }

        public synchronized void reset() {
            primaryHost = null;
            primaryBaseDomain = null;
            lastBlockedHost = null;
            lastBlockedAt = 0L;
        }

        private static String baseDomainOf(String host) {
            if (host == null) return null;
            host = host.toLowerCase(Locale.US);
            String[] parts = host.split("\\.");
            if (parts.length <= 2) return host;

            String tld = parts[parts.length - 1];
            String sld = parts[parts.length - 2];
            if (tld.length() == 2
                    && (sld.equals("co") || sld.equals("com") || sld.equals("org")
                    || sld.equals("net") || sld.equals("gov") || sld.equals("ac")
                    || sld.equals("edu"))
                    && parts.length >= 3) {
                return parts[parts.length - 3] + "." + sld + "." + tld;
            }
            return sld + "." + tld;
        }

        private static String firstLabelOf(String host) {
            if (host == null) return null;
            int dot = host.indexOf('.');
            return dot > 0 ? host.substring(0, dot) : host;
        }
    }
}