package com.bulknote.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

public class MainActivity extends Activity {
    private static final int PICK_MODEL = 701;
    private WebView web;
    private volatile boolean copyingModel = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
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
        @JavascriptInterface public String loadAI() {
            File f = modelFile();
            if (!f.exists()) return "먼저 GGUF 모델을 선택해 주세요.";
            try {
                int code = NativeInference.nativeLoad(f.getAbsolutePath());
                return code == 0 ? "AI 코치 준비 완료" : "모델 로드 실패 (코드 " + code + ")";
            } catch (Throwable e) { return "AI 초기화 실패: " + e.getMessage(); }
        }
        @JavascriptInterface public String askAI(String prompt) {
            if (prompt == null || prompt.trim().isEmpty()) return "질문을 입력해 주세요.";
            try { return NativeInference.nativeGenerate(prompt); }
            catch (Throwable e) { return "AI 답변 생성 실패: " + e.getMessage(); }
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
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}