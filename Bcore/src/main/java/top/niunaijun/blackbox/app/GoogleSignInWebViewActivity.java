package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import top.niunaijun.blackbox.BlackBoxCore;
import java.util.UUID;

public class GoogleSignInWebViewActivity extends Activity {
    private static final String CLIENT_ID = "697261581904-997ch5oh85im8rcq2lt172jbu92gjha6.apps.googleusercontent.com";
    private static final String FIREBASE_ORIGIN = "https://api-project-697261581904.firebaseapp.com";
    private static final String REDIRECT_URI = "https://api-project-697261581904.firebaseapp.com/__/auth/handler";
    private boolean tokenHandled = false;
    private WebView webView;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        String str = "https://accounts.google.com/o/oauth2/v2/auth?client_id=697261581904-997ch5oh85im8rcq2lt172jbu92gjha6.apps.googleusercontent.com&redirect_uri=" + Uri.encode(REDIRECT_URI) + "&response_type=id_token&scope=openid%20email%20profile&nonce=" + UUID.randomUUID().toString().replace("-", "");
        WebView webView = new WebView(this);
        this.webView = webView;
        setContentView(webView);
        WebSettings settings = this.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        String userAgentString = settings.getUserAgentString();
        if (userAgentString != null) {
            settings.setUserAgentString(userAgentString.replace("; wv", ""));
        }
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(this.webView, true);
        this.webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onHash(String str2) {
                if (str2 == null || !str2.contains("id_token=")) {
                    return;
                }
                GoogleSignInWebViewActivity.this.extractAndDeliver(str2);
            }
        }, "TokenBridge");
        this.webView.setWebChromeClient(new WebChromeClient());
        this.webView.setWebViewClient(new AnonymousClass2());
        this.webView.loadUrl(str);
    }

    class AnonymousClass2 extends WebViewClient {
        @Override
        public void onReceivedError(WebView webView, WebResourceRequest webResourceRequest, WebResourceError webResourceError) {
        }

        AnonymousClass2() {
        }

        @Override
        public void onPageFinished(WebView webView, String str) {
            super.onPageFinished(webView, str);
            if (str == null || !str.startsWith(GoogleSignInWebViewActivity.REDIRECT_URI)) {
                return;
            }
            webView.evaluateJavascript("(function(){ return window.location.hash; })()", new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value != null) {
                        String strReplace = value.replace("\"", "");
                        if (strReplace.contains("id_token=")) {
                            GoogleSignInWebViewActivity.this.extractAndDeliver(strReplace);
                        }
                    }
                }
            });
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest webResourceRequest) {
            String fragment;
            Uri url = webResourceRequest.getUrl();
            if (url == null || !url.toString().startsWith(GoogleSignInWebViewActivity.REDIRECT_URI) || (fragment = url.getFragment()) == null || !fragment.contains("id_token=")) {
                return false;
            }
            GoogleSignInWebViewActivity.this.extractAndDeliver(fragment);
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void extractAndDeliver(String str) {
        String strSubstring;
        if (this.tokenHandled) {
            return;
        }
        this.tokenHandled = true;
        String[] strArrSplit = str.replace("#", "").split("&");
        int length = strArrSplit.length;
        int i = 0;
        while (true) {
            if (i >= length) {
                strSubstring = null;
                break;
            }
            String str2 = strArrSplit[i];
            if (str2.startsWith("id_token=")) {
                strSubstring = str2.substring(9);
                break;
            }
            i++;
        }
        if (strSubstring == null || strSubstring.isEmpty()) {
            Intent intent = new Intent(GoogleSignInHelper.ACTION_GSI_CANCEL);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } else {
            GoogleSignInHelper.cacheToken(BlackBoxCore.getContext(), strSubstring);
            Intent intent2 = new Intent(GoogleSignInHelper.ACTION_GSI_TOKEN);
            intent2.putExtra("id_token", strSubstring);
            intent2.setPackage(getPackageName());
            sendBroadcast(intent2);
        }
        bringGameToFront();
        finish();
    }

    @Override
    public void onBackPressed() {
        WebView webView = this.webView;
        if (webView != null && webView.canGoBack()) {
            this.webView.goBack();
            return;
        }
        Intent intent = new Intent(GoogleSignInHelper.ACTION_GSI_CANCEL);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        bringGameToFront();
        super.onBackPressed();
    }

    @Override
    public void onDestroy() {
        WebView webView = this.webView;
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private void bringGameToFront() {
        try {
            ActivityManager activityManager = (ActivityManager) getSystemService("activity");
            if (activityManager == null) {
                return;
            }
            int taskId = getTaskId();
            for (ActivityManager.AppTask appTask : activityManager.getAppTasks()) {
                if (appTask.getTaskInfo().id != taskId) {
                    appTask.moveToFront();
                    return;
                }
            }
        } catch (Exception unused) {
        }
    }
}
