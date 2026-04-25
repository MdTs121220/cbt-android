package com.edutimurkasuari.cbt;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView     webView;
    private ProgressBar progressBar;
    private Handler     handler = new Handler();

    private static final String ALLOWED_DOMAIN  = "edu.timurkasuari.com";
    private static final String START_URL       = "https://edu.timurkasuari.com/cbt/";
    private static final String PIN_API_URL     = "https://edu.timurkasuari.com/cbt/siswa/get_kiosk_pin.php";
    private static final String AUTO_SUBMIT_URL = "https://edu.timurkasuari.com/cbt/siswa/exit_submit.php";
    private static final String APK_PING_URL    = "https://edu.timurkasuari.com/cbt/siswa/apk_ping.php";

    private String  kioskPin        = "1234";
    private String  currentExamId   = "";
    private String  currentCsrfToken= "";
    private boolean examActive       = false;
    private boolean pinDialogShowing = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        hideSystemUI();
        webView     = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        String defaultUA = s.getUserAgentString();
        s.setUserAgentString(defaultUA + " EDUKasuariExamBrowser/1.0 Android");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void setExamState(String examId, String csrf, boolean active) {
                currentExamId    = examId;
                currentCsrfToken = csrf;
                examActive       = active;
                if (active && !examId.isEmpty()) fetchKioskPin();
            }
        }, "CBTKiosk");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String host = req.getUrl().getHost();
                if (host != null && host.endsWith(ALLOWED_DOMAIN)) return false;
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                view.evaluateJavascript("(function(){var ei=document.querySelector('[name=exam_id]');var csrf=document.querySelector('[name=_csrf]');var isExam=window.location.href.indexOf('/siswa/ujian.php')>-1;if(window.CBTKiosk){window.CBTKiosk.setExamState(ei?ei.value:'',csrf?csrf.value:'',isExam);}})();", null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progressBar == null) return;
                if (progress < 100) { progressBar.setVisibility(View.VISIBLE); progressBar.setProgress(progress); }
                else progressBar.setVisibility(View.GONE);
            }
        });
        webView.loadUrl(START_URL);
        // TIDAK pakai startLockTask() — agar PIN dialog tetap bisa muncul
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) { webView.goBack(); return; }
        if (examActive) showPinDialog();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SEARCH:
                if (examActive) showPinDialog();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SEARCH:
                return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        if (examActive && !currentExamId.isEmpty()) pingServer("warning");
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (examActive && !currentExamId.isEmpty()) {
            pingServer("exited");
            submitExamFromBackground();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        hideSystemUI();
        if (examActive && !currentExamId.isEmpty()) pingServer("normal");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    private void showPinDialog() {
        if (pinDialogShowing) return;
        pinDialogShowing = true;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                LinearLayout layout = new LinearLayout(MainActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                int pad = (int)(20 * getResources().getDisplayMetrics().density);
                layout.setPadding(pad, pad, pad, 0);
                TextView msg = new TextView(MainActivity.this);
                msg.setText("Masukkan PIN dari Proktor untuk keluar dari ujian");
                msg.setTextSize(14);
                msg.setPadding(0, 0, 0, pad/2);
                layout.addView(msg);
                final EditText pinInput = new EditText(MainActivity.this);
                pinInput.setHint("PIN Proktor");
                pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                pinInput.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                pinInput.setTextSize(24);
                layout.addView(pinInput);
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Keluar dari Ujian")
                    .setView(layout)
                    .setCancelable(false)
                    .setPositiveButton("Keluar", null)
                    .setNegativeButton("Kembali ke Soal", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { pinDialogShowing = false; d.dismiss(); }
                    }).create();
                dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                    @Override public void onShow(final DialogInterface d) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                String entered = pinInput.getText().toString().trim();
                                if (entered.equals(kioskPin)) {
                                    pinDialogShowing = false;
                                    d.dismiss();
                                    finish();
                                } else {
                                    pinInput.setText("");
                                    pinInput.setError("PIN salah!");
                                    Toast.makeText(MainActivity.this, "PIN salah!", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                });
                dialog.show();
            }
        });
    }

    private void pingServer(final String status) {
        final String examId = currentExamId;
        final String csrf   = currentCsrfToken;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL(APK_PING_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    String cookies = CookieManager.getInstance().getCookie(APK_PING_URL);
                    if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                    String body = "exam_id=" + examId + "&status=" + status + "&_csrf=" + csrf;
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush(); os.close();
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception e) {}
            }
        }).start();
    }

    private void submitExamFromBackground() {
        final String examId = currentExamId;
        final String csrf   = currentCsrfToken;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL(AUTO_SUBMIT_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    String cookies = CookieManager.getInstance().getCookie(AUTO_SUBMIT_URL);
                    if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                    String body = "exam_id=" + examId + "&_csrf=" + csrf + "&source=apk_exit_stop";
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush(); os.close();
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception e) {}
            }
        }).start();
    }

    private void fetchKioskPin() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL(PIN_API_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    String cookies = CookieManager.getInstance().getCookie(PIN_API_URL);
                    if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                    if (conn.getResponseCode() == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        JSONObject json = new JSONObject(sb.toString());
                        if ("ok".equals(json.optString("status"))) kioskPin = json.optString("pin", "1234");
                    }
                    conn.disconnect();
                } catch (Exception e) {}
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
    }
}
