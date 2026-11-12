package com.bulknote.app;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.app.AlarmManager;
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
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int CREATE_EXPORT = 702;
    private String pendingExportContent = "";
    private WebView web;
    private boolean pendingOpenWorkout;
    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(3);
    private static final String AI_PREFS = "bulknote_ai_secure";
    private static final String AI_KEY_ALIAS = "BulkNoteGroqApiKeyV1";
    private static final String GEMINI_PREFS = "bulknote_gemini_secure";
    private static final String GEMINI_KEY_ALIAS = "BulkNoteGeminiApiKeyV1";
    private final java.util.HashMap<String, HttpURLConnection> geminiConns = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Remove the legacy local Qwen model file after retiring the unreliable in-app AI feature.
        File oldModel = new File(getFilesDir(), "qwen-model.gguf");
        File oldModelTemp = new File(getFilesDir(), "qwen-model.tmp");
        if (oldModel.exists()) oldModel.delete();
        if (oldModelTemp.exists()) oldModelTemp.delete();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 703);
        }
        pendingOpenWorkout = getIntent() != null && getIntent().getBooleanExtra("open_workout_next_set", false);
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

        private SecretKey getOrCreateAiKey() throws Exception {
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            if(ks.containsAlias(AI_KEY_ALIAS)) return (SecretKey)ks.getKey(AI_KEY_ALIAS,null);
            KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(AI_KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            return kg.generateKey();
        }
        private String readAiKey() throws Exception {
            SharedPreferences p=getSharedPreferences(AI_PREFS,MODE_PRIVATE);
            String ivText=p.getString("iv",null), dataText=p.getString("data",null);
            if(ivText==null||dataText==null)return "";
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,getOrCreateAiKey(),new GCMParameterSpec(128,Base64.decode(ivText,Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(dataText,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }
        @JavascriptInterface public void saveGroqApiKey(String key) {
            if(key==null||!key.trim().startsWith("gsk_")||key.trim().length()<20)throw new IllegalArgumentException("Groq API 키 형식을 확인해 주세요.");
            try {
                Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,getOrCreateAiKey());
                byte[] encrypted=c.doFinal(key.trim().getBytes(StandardCharsets.UTF_8));
                getSharedPreferences(AI_PREFS,MODE_PRIVATE).edit()
                    .putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP))
                    .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply();
            } catch(Exception e){throw new RuntimeException("암호화 저장 실패: "+e.getMessage());}
        }
        @JavascriptInterface public boolean hasGroqApiKey() {
            try{return !readAiKey().isEmpty();}catch(Exception e){return false;}
        }
        @JavascriptInterface public void deleteGroqApiKey() {
            getSharedPreferences(AI_PREFS,MODE_PRIVATE).edit().clear().apply();
            try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(AI_KEY_ALIAS))ks.deleteEntry(AI_KEY_ALIAS);}catch(Exception ignored){}
        }
        @JavascriptInterface public void askGroq(final String requestId,final String question,final String context) {
            aiExecutor.execute(() -> {
                HttpURLConnection conn=null;
                try {
                    String key=readAiKey(); if(key.isEmpty())throw new Exception("API 키가 설정되지 않았어요.");
                    URL url=new URL("https://api.groq.com/openai/v1/chat/completions");
                    conn=(HttpURLConnection)url.openConnection(); conn.setRequestMethod("POST");
                    conn.setConnectTimeout(10000); conn.setReadTimeout(25000); conn.setDoOutput(true);
                    conn.setRequestProperty("Authorization","Bearer "+key); conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                    JSONObject body=new JSONObject(); body.put("model","openai/gpt-oss-20b"); body.put("temperature",0.25); body.put("max_tokens",700); body.put("stream",false);
                    JSONArray msgs=new JSONArray();
                    msgs.put(new JSONObject().put("role","system").put("content","너는 한국어 근력운동·벌크업 코치다. 말투는 조선시대 어른이 쓰는 하오체(~하오, ~이오, ~하시오, ~했소)로 구수하게 통일한다. 제공된 기록에 근거해 짧고 구체적으로 답한다. 기록에 없는 수치나 자세를 추측하지 않는다. 답변은 핵심부터 말하고 3~6문장 또는 짧은 목록으로 작성한다. 통증·부상·질환은 진단하지 말고 중단 및 전문가 상담을 안내한다. 과도한 중량 증가나 위험한 운동을 권하지 않는다."));
                    String userText="기록 요약(JSON):\n"+(context==null?"{}":context)+"\n\n질문: "+(question==null?"":question);
                    msgs.put(new JSONObject().put("role","user").put("content",userText)); body.put("messages",msgs);
                    byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
                    conn.getOutputStream().write(payload); conn.getOutputStream().flush();
                    int code=conn.getResponseCode(); InputStream stream=(code>=200&&code<300)?conn.getInputStream():conn.getErrorStream();
                    ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n;
                    while(stream!=null&&(n=stream.read(buf))!=-1)out.write(buf,0,n);
                    String raw=new String(out.toByteArray(),StandardCharsets.UTF_8);
                    if(code<200||code>=300){
                        String detail="HTTP "+code;
                        try{detail=new JSONObject(raw).optJSONObject("error").optString("message",detail);}catch(Exception ignored){}
                        if(code==429)detail="무료 사용 한도에 도달했어요. 잠시 기다렸다 다시 시도해 주세요.";
                        throw new Exception(detail);
                    }
                    JSONObject result=new JSONObject(raw); String answer=result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","").trim();
                    if(answer.isEmpty())throw new Exception("AI 응답이 비어 있어요.");
                    notifyAiResult(requestId,true,answer);
                } catch(Exception e){notifyAiResult(requestId,false,e.getMessage()==null?"네트워크 요청 실패":e.getMessage());}
                finally{if(conn!=null)conn.disconnect();}
            });
        }
        private void notifyAiResult(String id,boolean ok,String message){
            if(web==null)return;
            final String safeId=JSONObject.quote(id==null?"":id), safeMsg=JSONObject.quote(message==null?"":message);
            runOnUiThread(() -> {if(web!=null)web.evaluateJavascript("window.onNativeAiResult&&window.onNativeAiResult("+safeId+","+ok+","+safeMsg+")",null);});
        }

        // ---------- Gemini (API 키는 기기 Keystore에만 암호화 저장, APK/HTML에는 없음) ----------
        private SecretKey getOrCreateGeminiKey() throws Exception {
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            if(ks.containsAlias(GEMINI_KEY_ALIAS)) return (SecretKey)ks.getKey(GEMINI_KEY_ALIAS,null);
            KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(GEMINI_KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            return kg.generateKey();
        }
        private String readGeminiKey() throws Exception {
            SharedPreferences p=getSharedPreferences(GEMINI_PREFS,MODE_PRIVATE);
            String ivText=p.getString("iv",null), dataText=p.getString("data",null);
            if(ivText==null||dataText==null)return "";
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,getOrCreateGeminiKey(),new GCMParameterSpec(128,Base64.decode(ivText,Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(dataText,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }
        @JavascriptInterface public void saveGeminiApiKey(String key) {
            if(key==null||!key.trim().matches("[A-Za-z0-9._~+/=-]{20,300}"))throw new IllegalArgumentException("Gemini API 키 형식을 확인해 주시오.");
            try {
                Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,getOrCreateGeminiKey());
                byte[] encrypted=c.doFinal(key.trim().getBytes(StandardCharsets.UTF_8));
                getSharedPreferences(GEMINI_PREFS,MODE_PRIVATE).edit()
                    .putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP))
                    .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply();
            } catch(Exception e){throw new RuntimeException("암호화 저장 실패: "+e.getMessage());}
        }
        @JavascriptInterface public boolean hasGeminiApiKey() {
            try{return !readGeminiKey().isEmpty();}catch(Exception e){return false;}
        }
        @JavascriptInterface public void deleteGeminiApiKey() {
            getSharedPreferences(GEMINI_PREFS,MODE_PRIVATE).edit().clear().apply();
            try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(GEMINI_KEY_ALIAS))ks.deleteEntry(GEMINI_KEY_ALIAS);}catch(Exception ignored){}
        }
        /** requestJson 은 Gemini generateContent 본문 그대로. 스트리밍 조각은 onNativeGemini(id,"chunk",json) 으로 전달. */
        @JavascriptInterface public void askGemini(final String requestId,final String model,final String requestJson) {
            aiExecutor.execute(() -> {
                HttpURLConnection conn=null;
                try {
                    String key=readGeminiKey(); if(key.isEmpty())throw new Exception("Gemini API 키가 설정되지 않았소.");
                    if(model==null||!model.matches("[A-Za-z0-9._-]{3,60}"))throw new Exception("모델 이름을 확인해 주시오.");
                    URL url=new URL("https://generativelanguage.googleapis.com/v1beta/models/"+model+":streamGenerateContent?alt=sse");
                    conn=(HttpURLConnection)url.openConnection(); conn.setRequestMethod("POST");
                    synchronized(geminiConns){geminiConns.put(requestId,conn);}
                    conn.setConnectTimeout(12000); conn.setReadTimeout(60000); conn.setDoOutput(true);
                    conn.setRequestProperty("x-goog-api-key",key); conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                    conn.getOutputStream().write(requestJson.getBytes(StandardCharsets.UTF_8)); conn.getOutputStream().flush();
                    int code=conn.getResponseCode();
                    if(code<200||code>=300) throw new Exception(geminiErrorText(code,conn.getErrorStream()));
                    BufferedReader br=new BufferedReader(new InputStreamReader(conn.getInputStream(),StandardCharsets.UTF_8));
                    String line;
                    while((line=br.readLine())!=null){
                        if(line.startsWith("data:")){String d=line.substring(5).trim(); if(!d.isEmpty())notifyGemini(requestId,"chunk",d);}
                    }
                    notifyGemini(requestId,"done","");
                } catch(Exception e){
                    boolean cancelled;
                    synchronized(geminiConns){cancelled=!geminiConns.containsKey(requestId);}
                    notifyGemini(requestId,cancelled?"cancel":"error",e.getMessage()==null?"네트워크 요청 실패":e.getMessage());
                } finally{
                    synchronized(geminiConns){geminiConns.remove(requestId);}
                    if(conn!=null)conn.disconnect();
                }
            });
        }
        private String geminiErrorText(int code, InputStream es) {
            String detail="HTTP "+code;
            try{
                ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n;
                while(es!=null&&(n=es.read(buf))!=-1)out.write(buf,0,n);
                detail=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getJSONObject("error").optString("message",detail);
            }catch(Exception ignored){}
            if(code==429)return "무료 사용 한도에 도달했소. 잠시 뒤 다시 시도하시오.";
            if(code==401)return "키가 인증되지 않았소(401): "+detail;
            if(code==400&&detail.toLowerCase().contains("api key"))return "API 키가 올바르지 않소: "+detail;
            if(code==403)return "이 키로는 사용할 수 없소(권한·지역 확인): "+detail;
            if(code==404)return "모델을 찾지 못했소. 설정에서 모델 이름을 확인하시오.";
            return detail;
        }
        /** 키로 쓸 수 있는 모델 목록 조회. 결과 JSON 은 onNativeGemini(id,"models",json) 으로 전달. */
        @JavascriptInterface public void listGeminiModels(final String requestId) {
            aiExecutor.execute(() -> {
                HttpURLConnection conn=null;
                try {
                    String key=readGeminiKey(); if(key.isEmpty())throw new Exception("Gemini API 키가 설정되지 않았소.");
                    URL url=new URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200");
                    conn=(HttpURLConnection)url.openConnection(); conn.setRequestMethod("GET");
                    conn.setConnectTimeout(12000); conn.setReadTimeout(20000);
                    conn.setRequestProperty("x-goog-api-key",key);
                    int code=conn.getResponseCode();
                    if(code<200||code>=300) throw new Exception(geminiErrorText(code,conn.getErrorStream()));
                    ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n; InputStream is=conn.getInputStream();
                    while((n=is.read(buf))!=-1)out.write(buf,0,n);
                    notifyGemini(requestId,"models",new String(out.toByteArray(),StandardCharsets.UTF_8));
                    notifyGemini(requestId,"done","");
                } catch(Exception e){ notifyGemini(requestId,"error",e.getMessage()==null?"네트워크 요청 실패":e.getMessage()); }
                finally{ if(conn!=null)conn.disconnect(); }
            });
        }
        @JavascriptInterface public void cancelGemini(final String requestId) {
            HttpURLConnection c; synchronized(geminiConns){c=geminiConns.remove(requestId);}
            if(c!=null){final HttpURLConnection cc=c; new Thread(() -> {try{cc.disconnect();}catch(Exception ignored){}}).start();}
        }
        private void notifyGemini(String id,String type,String payload){
            if(web==null)return;
            final String a=JSONObject.quote(id==null?"":id), b=JSONObject.quote(type), c=JSONObject.quote(payload==null?"":payload);
            runOnUiThread(() -> {if(web!=null)web.evaluateJavascript("window.onNativeGemini&&window.onNativeGemini("+a+","+b+","+c+")",null);});
        }

        @JavascriptInterface public boolean hasExactAlarmAccess() {
            if (Build.VERSION.SDK_INT < 31) return true;
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        }

        @JavascriptInterface public void requestExactAlarmAccess() {
            if (Build.VERSION.SDK_INT < 31) return;
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }

        @JavascriptInterface public void scheduleRestNotification(final long endAtMillis) {
            scheduleWorkoutNotification(endAtMillis, false);
        }

        @JavascriptInterface public void scheduleExerciseNotification(final long endAtMillis) {
            scheduleWorkoutNotification(endAtMillis, true);
        }

        private void scheduleWorkoutNotification(final long endAtMillis, final boolean exerciseTimer) {
            if (endAtMillis > System.currentTimeMillis()) {
                RestAlarmReceiver.schedule(MainActivity.this, endAtMillis, exerciseTimer);
                if (Build.VERSION.SDK_INT >= 31) {
                    AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                    // Never silently rely on an inexact Doze-delayed alarm. Ask again on
                    // each new rest session until the user grants exact-alarm access.
                    if (am != null && !am.canScheduleExactAlarms()) requestExactAlarmAccess();
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null && !nm.canUseFullScreenIntent()) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:" + getPackageName()));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        @JavascriptInterface public void cancelRestNotification() {
            RestAlarmReceiver.cancel(MainActivity.this);
        }

        @JavascriptInterface public void cancelScheduledRestAlarm() {
            RestAlarmReceiver.cancelScheduled(MainActivity.this);
        }

        @JavascriptInterface public void showWorkoutStatus(String title, String detail) {
            RestAlarmReceiver.showWorkoutStatus(MainActivity.this, title, detail);
        }

        @JavascriptInterface public void stopWorkoutStatus() {
            RestAlarmReceiver.stopWorkoutStatus(MainActivity.this);
        }

        @JavascriptInterface public void vibrateShort() {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                    if (manager != null) {
                        Vibrator vibrator = manager.getDefaultVibrator();
                        if (vibrator != null && vibrator.hasVibrator())
                            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 100, 300}, -1));
                    }
                } else {
                    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= 26)
                            vibrator.vibrate(VibrationEffect.createOneShot(110, VibrationEffect.DEFAULT_AMPLITUDE));
                        else vibrator.vibrate(new long[]{0, 300, 100, 300}, -1);
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
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("open_workout_next_set", false)) {
            pendingOpenWorkout = true;
            if (web != null) web.postDelayed(this::openWorkoutFromNotification, 250);
        }
    }
    private void openWorkoutFromNotification() {
        if (!pendingOpenWorkout || web == null) return;
        pendingOpenWorkout = false;
        web.evaluateJavascript("if (window.openWorkoutFromWidget) window.openWorkoutFromWidget();", null);
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.postDelayed(() -> {
                web.evaluateJavascript("if (window.rearmRestAlarmAfterResume) window.rearmRestAlarmAfterResume(); if (window.restoreWorkoutPosition) window.restoreWorkoutPosition();", null);
                openWorkoutFromNotification();
            }, 500);
        }
    }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}
