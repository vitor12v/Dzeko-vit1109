package br.com.dzeko.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Dzeko for Android: shows the Dzeko web app (always the newest version from the site)
 * and gives it a native bridge, so commands run directly on the phone without a tap.
 */
public class MainActivity extends Activity {

    static final String HOME = "https://vitor12v.github.io/Dzeko-vit1109/";
    static final String HOST = "vitor12v.github.io";

    static final int REQ_MIC = 10, REQ_GEO = 11, REQ_CALL = 12, REQ_CAL = 13, REQ_START = 14;

    WebView web;
    Bridge bridge;

    private PermissionRequest pendingWebMic;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#050A13"));
        getWindow().setNavigationBarColor(Color.parseColor("#050A13"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#050A13"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " DzekoAndroid/1");

        bridge = new Bridge(this);
        web.addJavascriptInterface(bridge, "DzekoAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equalsIgnoreCase(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/Dzeko-vit1109")) return false;
                // Everything else (WhatsApp, Spotify, Maps, Google, tel:, mailto:...) goes to the right app.
                bridge.openUri(u);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (has(Manifest.permission.RECORD_AUDIO)) request.grant(request.getResources());
                    else {
                        pendingWebMic = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) callback.invoke(origin, true, true);
                else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_GEO);
                }
            }
        });

        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_START);
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
    }

    boolean has(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    void ask(String[] permissions, int code) {
        runOnUiThread(() -> requestPermissions(permissions, code));
    }

    void keepScreenOn(boolean on) {
        runOnUiThread(() -> {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    void showOffline() {
        String html = "<html><body style='background:#050A13;color:#DCE7F6;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>"
                + "<div style='font-size:28px;letter-spacing:.3em;color:#4DB4FF'>DZEKO</div>"
                + "<p style='color:#7D91AE;max-width:280px'>Sem conexão com a internet agora. Confira o Wi-Fi ou os dados móveis.</p>"
                + "<a href='" + HOME + "' style='color:#03101F;background:#4DB4FF;padding:14px 24px;text-decoration:none;letter-spacing:.2em'>TENTAR DE NOVO</a>"
                + "</body></html>";
        web.loadDataWithBaseURL(HOME, html, "text/html", "UTF-8", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        switch (requestCode) {
            case REQ_MIC:
                if (pendingWebMic != null) {
                    if (ok) pendingWebMic.grant(pendingWebMic.getResources());
                    else pendingWebMic.deny();
                    pendingWebMic = null;
                }
                break;
            case REQ_GEO:
                if (pendingGeoCallback != null) {
                    pendingGeoCallback.invoke(pendingGeoOrigin, ok, ok);
                    pendingGeoCallback = null;
                }
                break;
            case REQ_CALL:
                bridge.onCallPermission(ok);
                break;
            case REQ_CAL:
                bridge.onCalendarPermission(ok);
                break;
            default:
                break;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
    }

    @Override
    protected void onPause() {
        bridge.stopListening();
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        bridge.shutdown();
        web.removeJavascriptInterface("DzekoAndroid");
        web.destroy();
        super.onDestroy();
    }

    /** Sends an event from the phone back into the web app. */
    void emit(String channel, String type, String json) {
        final String js = "window.__dzkNative&&window.__dzkNative(" + org.json.JSONObject.quote(channel) + ","
                + org.json.JSONObject.quote(type) + "," + (json == null ? "{}" : json) + ")";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }
}
