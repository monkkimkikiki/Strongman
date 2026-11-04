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
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_MODEL = 701;
    private static final int CREATE_EXPORT = 702;
    private String pendingExportName = "BulkNote_Analysis.json";
    private WebView web;
    private volatile boolean copyingModel = false;
    private volatile boolean aiBusy = false;
    private volatile String pendingExportContent = "";
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); return true;
                }
                return false;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");
    }

    private File modelFile() { return new File(getFilesDir(), "qwen-model.gguf"); }

    private class NativeInference {
        static { System.loadLibrary("bulknote-ai"); }
        static native int nativeLoad(String path);
        static native String nativeGenerate(String prompt);
        static native void nativeUnload();
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
                        Vibrator v = manager.getDefaultVibrator();
                        if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(110, VibrationEffect.DEFAULT_AMPLITUDE));
                    }
                } else {
                    Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(110, VibrationEffect.DEFAULT_AMPLITUDE));
                        else v.vibrate(110);
                    }
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public void saveTextFile(final String fileName, final String content) {
            pendingExportContent = content == null ? "" : content;
            runOnUiThread(() -> {
                try {
                    pendingExportName = (fileName == null || fileName.trim().isEmpty()) ? "BulkNote_Analysis.json" : fileName;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/json");
                    i.putExtra(Intent.EXTRA_TITLE, pendingExportName);
                    startActivityForResult(i, CREATE_EXPORT);
                } catch (Exception e) { notifyJs("exportDone", "파일 저장 창을 열지 못했어요: " + e.getMessage()); }
            });
        }
        @JavascriptInterface public void share(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text); startActivity(Intent.createChooser(i, "백업 보내기"));
            });
        }
        @JavascriptInterface public void open(final String url) {
            if (url == null || !url.startsWith("https://")) return;
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {} });
        }
        @JavascriptInterface public void chooseModel() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/x-gguf"});
                startActivityForResult(i, PICK_MODEL);
            });
        }
        @JavascriptInterface public void startLoadAI() {
            final File f = modelFile();
            if (!f.exists()) { notifyJs("loadDone", "먼저 GGUF 모델을 선택해 주세요."); return; }
            if (aiBusy) { notifyJs("loadDone", "AI 작업이 이미 진행 중이에요."); return; }
            aiBusy = true;
            notifyJs("loadProgress", "모델을 메모리에 올리는 중입니다. 첫 실행은 수 분 걸릴 수 있어요.");
            aiExecutor.execute(() -> {
                try {
                    int code = NativeInference.nativeLoad(f.getAbsolutePath());
                    notifyJs("loadDone", code == 0 ? "AI 코치 준비 완료" : "모델 로드 실패 (코드 " + code + ")");
                } catch (Throwable e) {
                    notifyJs("loadDone", "AI 초기화 실패: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally { aiBusy = false; }
            });
        }
        @JavascriptInterface public void startAskAI(final String prompt) {
            if (prompt == null || prompt.trim().isEmpty()) { notifyJs("answerDone", "질문을 입력해 주세요."); return; }
            if (aiBusy) { notifyJs("answerDone", "다른 AI 작업이 진행 중이에요. 완료될 때까지 기다려 주세요."); return; }
            aiBusy = true;
            notifyJs("answerProgress", "질문을 처리하고 있어요. 모델 크기와 휴대폰 성능에 따라 수 분 걸릴 수 있습니다.");
            aiExecutor.execute(() -> {
                try {
                    String answer = NativeInference.nativeGenerate(prompt);
                    notifyJs("answerDone", answer == null || answer.trim().isEmpty() ? "답변이 비어 있어요. 질문을 짧게 바꿔 다시 시도해 주세요." : answer);
                } catch (Throwable e) {
                    notifyJs("answerDone", "AI 답변 생성 실패: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally { aiBusy = false; }
            });
        }
        @JavascriptInterface public void unloadAI() {
            try { NativeInference.nativeUnload(); } catch (Throwable ignored) {}
        }
        @JavascriptInterface public String modelStatus() {
            File f = modelFile();
            return f.exists() ? "선택됨 · " + (f.length() / (1024 * 1024)) + "MB" : "모델을 선택해 주세요";
        }
        @JavascriptInterface public void removeModel() {
            try { NativeInference.nativeUnload(); } catch (Throwable ignored) {}
            File f = modelFile(); if (f.exists()) f.delete();
            notifyJs("status", "모델 파일을 삭제했어요");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_EXPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) { notifyJs("exportDone", "저장을 취소했어요."); return; }
            final Uri outUri = data.getData();
            final String content = pendingExportContent;
            new Thread(() -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(outUri)) {
                    if (out == null) throw new Exception("저장 위치를 열 수 없어요");
                    out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.flush();
                    notifyJs("exportDone", "분석 파일 저장 완료! 이 채팅에 파일을 첨부해 주세요.");
                } catch (Exception e) { notifyJs("exportDone", "파일 저장 실패: " + e.getMessage()); }
            }).start();
            return;
        }
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            copyingModel = true; notifyJs("status", "모델 파일을 앱 내부에 복사하는 중이에요. 용량에 따라 시간이 걸려요.");
            File tmp = new File(getFilesDir(), "qwen-model.tmp");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new Exception("파일을 열 수 없어요");
                byte[] buf = new byte[1024 * 1024]; int n; long total = 0;
                while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); total += n; }
                out.flush();
                String name = "";
                try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) { int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ix >= 0) name = c.getString(ix); }
                }
                if (!name.toLowerCase().endsWith(".gguf")) throw new Exception("GGUF 파일(.gguf)을 선택해 주세요");
                File dest = modelFile(); if (dest.exists()) dest.delete();
                if (!tmp.renameTo(dest)) throw new Exception("내부 저장소로 이동하지 못했어요");
                notifyJs("status", "모델 파일 저장 완료 · " + (total / (1024 * 1024)) + "MB. AI 코치 시작 버튼을 눌러주세요.");
            } catch (Exception e) {
                if (tmp.exists()) tmp.delete();
                notifyJs("status", "모델 저장 실패: " + e.getMessage());
            } finally { copyingModel = false; }
        }).start();
    }

    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { aiExecutor.shutdownNow(); if (web != null) web.destroy(); super.onDestroy(); }
}