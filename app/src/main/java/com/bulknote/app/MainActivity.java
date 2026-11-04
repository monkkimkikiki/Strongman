package com.bulknote.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int CREATE_EXPORT = 702;
    private String pendingExportContent = "";
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Remove the legacy local Qwen model file after retiring the unreliable in-app AI feature.
        File oldModel = new File(getFilesDir(), "qwen-model.gguf");
        File oldModelTemp = new File(getFilesDir(), "qwen-model.tmp");
        if (oldModel.exists()) oldModel.delete();
        if (oldModelTemp.exists()) oldModelTemp.delete();
        web = new WebView(this);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");
    }

    private void notifyJs(final String method, final String value) {
        if (web == null) return;
        final String safe = org.json.JSONObject.quote(value == null ? "" : value);
        runOnUiThread(() -> web.evaluateJavascript("window.onNativeModelEvent && window.onNativeModelEvent('" + method + "'," + safe + ")", null));
    }

    private class Bridge {
        @JavascriptInterface public String ping() { return "BulkNote AndroidBridge OK"; }

        @JavascriptInterface public void vibrateShort() {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                    if (manager != null) {
                        Vibrator vibrator = manager.getDefaultVibrator();
                        if (vibrator != null && vibrator.hasVibrator())
                            vibrator.vibrate(VibrationEffect.createOneShot(110, VibrationEffect.DEFAULT_AMPLITUDE));
                    }
                } else {
                    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= 26)
                            vibrator.vibrate(VibrationEffect.createOneShot(110, VibrationEffect.DEFAULT_AMPLITUDE));
                        else vibrator.vibrate(110);
                    }
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public void saveTextFile(final String fileName, final String content) {
            pendingExportContent = content == null ? "" : content;
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE,
                        fileName == null || fileName.trim().isEmpty() ? "BulkNote_Analysis.json" : fileName);
                    startActivityForResult(intent, CREATE_EXPORT);
                } catch (Exception e) {
                    notifyJs("exportDone", "파일 저장 창을 열지 못했어요: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface public void share(final String text) {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(intent, "백업 보내기"));
            });
        }

        @JavascriptInterface public void open(final String url) {
            if (url == null || !url.startsWith("https://")) return;
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ignored) {}
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_EXPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExportContent = "";
            notifyJs("exportDone", "저장을 취소했어요.");
            return;
        }
        final Uri outputUri = data.getData();
        final String content = pendingExportContent;
        pendingExportContent = "";
        new Thread(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(outputUri)) {
                if (output == null) throw new Exception("저장 위치를 열 수 없어요");
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.flush();
                notifyJs("exportDone", "분석 파일 저장 완료! 이 채팅에 JSON 파일을 첨부해 주세요.");
            } catch (Exception e) {
                notifyJs("exportDone", "파일 저장 실패: " + e.getMessage());
            }
        }).start();
    }

    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}
