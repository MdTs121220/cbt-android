package com.edutimurkasuari.cbt;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
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
import android.widget.ProgressBar;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private WebView webView;
    private ProgressBar progressBar;
    private Handler handler = new Handler();
    private Runnable autoSubmitRunnable;
    private static final String ALLOWED_DOMAIN  = "edu.timurkasuari.com";
    private static final String START_URL       = "https://edu.timurkasuari.com/cbt/";
    private static final String AUTO_SUBMIT_URL = "https://edu.timurkasuari.com/cbt/siswa/exit_submit.php";
    private String  currentExamId    = "";
    private String  currentCsrfToken = "";
    private boolean examActive        = false;
    private static final int EXIT_GRACE_SECONDS = 15;
    private static final String NOTIF_CHANNEL   = "cbt_exit";
    private static final int    NOTIF_ID        = 1001;

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
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void setExamState(String examId, String csrf, boolean active) {
                currentExamId    = examId;
                currentCsrfToken = csrf;
                examActive       = active;
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
                view.evaluateJavascript(
                    "(function(){" +
                    "var ei=document.querySelector('[name=exam_id]');" +
                    "var csrf=document.querySelector('[name=_csrf]');" +
                    "var isExam=window.location.href.indexOf('/siswa/ujian.php')>-1;" +
                    "if(window.CBTKiosk){window.CBTKiosk.setExamState(ei?ei.value:'',csrf?csrf.value:'',isExam);}" +
                    "})();", null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progressBar == null) return;
                if (progress < 100) { progressBar.setVisibility(View.VISIBLE); progressBar.setProgress(progress); }
                else { progressBar.setVisibility(View.GONE); }
            }
        });
        webView.loadUrl(START_URL);
        setupNotificationChannel();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_SEARCH: return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_SEARCH: return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (examActive && !currentExamId.isEmpty()) startExitCountdown();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        hideSystemUI();
        cancelExitCountdown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    private void startExitCountdown() {
        cancelExitCountdown();
        showExitNotification(EXIT_GRACE_SECONDS);
        autoSubmitRunnable = new Runnable() {
            int remaining = EXIT_GRACE_SECONDS;
            @Override public void run() {
                remaining--;
                if (remaining <= 0) {
                    cancelNotification();
                    submitExamFromBackground();
                } else {
                    showExitNotification(remaining);
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.postDelayed(autoSubmitRunnable, 1000);
    }

    private void cancelExitCountdown() {
        if (autoSubmitRunnable != null) {
            handler.removeCallbacks(autoSubmitRunnable);
            autoSubmitRunnable = null;
        }
        cancelNotification();
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
                    conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                    String body = "exam_id="+examId+"&_csrf="+csrf+"&source=apk_exit";
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush(); os.close();
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception e) { /* server handle via end_datetime */ }
            }
        }).start();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(NOTIF_CHANNEL,"CBT Peringatan Ujian",NotificationManager.IMPORTANCE_HIGH);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void showExitNotification(int sec) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("PERINGATAN UJIAN!")
            .setContentText("Kembali dalam "+sec+" detik atau ujian dikumpulkan otomatis!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false).setOngoing(true).setContentIntent(pi);
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID,b.build()); }
        catch (SecurityException e) { /* no permission */ }
    }

    private void cancelNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIF_ID);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN
            |View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
    }
}
