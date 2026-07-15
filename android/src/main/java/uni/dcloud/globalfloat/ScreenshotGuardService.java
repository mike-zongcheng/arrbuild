package uni.dcloud.globalfloat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 真正的前台服务：后台扫描截屏 → 阿里云 OCR → 题库定位 → DeepSeek → 悬浮通知
 * 不依赖 UniApp WebView JS（单屏后台 JS 会被 pauseTimers）。
 */
public class ScreenshotGuardService extends Service {
    private static final String TAG = "ScreenshotGuard";
    private static final String PREF = "zuobi_screenshot_guard";
    private static final String CHANNEL_GUARD = "zuobi_guard_fgs";
    private static final String CHANNEL_RESULT = "zuobi_guard_result";
    private static final int NOTIFY_FGS = 30101;
    private static final int NOTIFY_RESULT = 30102;

    private static volatile boolean running = false;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private Handler mainHandler;
    private ContentObserver observer;
    private boolean processing = false;
    private long lastHandledId = 0L;
    private long watchStartMs = 0L;

    private String accessKeyId = "";
    private String accessKeySecret = "";
    private String ocrEndpoint = "ocr-api.cn-hangzhou.aliyuncs.com";
    private String deepseekKey = "";
    private String deepseekBase = "https://api.deepseek.com";
    private String deepseekModel = "deepseek-chat";
    private String questionsPath = "";
    private double matchThreshold = 0.42;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("zuobi-shot-guard");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        startAsForeground("截屏搜题守护中", "后台自动识图作答");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("stop".equals(intent.getStringExtra("action"))) {
                stopSelf();
                return START_NOT_STICKY;
            }
            accessKeyId = safe(intent.getStringExtra("accessKeyId"), accessKeyId);
            accessKeySecret = safe(intent.getStringExtra("accessKeySecret"), accessKeySecret);
            ocrEndpoint = safe(intent.getStringExtra("ocrEndpoint"), ocrEndpoint);
            deepseekKey = safe(intent.getStringExtra("deepseekKey"), deepseekKey);
            deepseekBase = safe(intent.getStringExtra("deepseekBase"), deepseekBase);
            deepseekModel = safe(intent.getStringExtra("deepseekModel"), deepseekModel);
            questionsPath = safe(intent.getStringExtra("questionsPath"), questionsPath);
            matchThreshold = intent.getDoubleExtra("matchThreshold", matchThreshold);

            SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
            sp.edit()
                .putString("accessKeyId", accessKeyId)
                .putString("accessKeySecret", accessKeySecret)
                .putString("ocrEndpoint", ocrEndpoint)
                .putString("deepseekKey", deepseekKey)
                .putString("deepseekBase", deepseekBase)
                .putString("deepseekModel", deepseekModel)
                .putString("questionsPath", questionsPath)
                .putFloat("matchThreshold", (float) matchThreshold)
                .apply();
        } else {
            loadPrefs();
        }

        watchStartMs = System.currentTimeMillis();
        registerObserver();
        workerHandler.removeCallbacks(pollRunnable);
        workerHandler.post(pollRunnable);
        return START_STICKY;
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        accessKeyId = sp.getString("accessKeyId", "");
        accessKeySecret = sp.getString("accessKeySecret", "");
        ocrEndpoint = sp.getString("ocrEndpoint", ocrEndpoint);
        deepseekKey = sp.getString("deepseekKey", "");
        deepseekBase = sp.getString("deepseekBase", deepseekBase);
        deepseekModel = sp.getString("deepseekModel", deepseekModel);
        questionsPath = sp.getString("questionsPath", "");
        matchThreshold = sp.getFloat("matchThreshold", 0.42f);
    }

    @Override
    public void onDestroy() {
        running = false;
        unregisterObserver();
        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                scanLatestScreenshot("poll");
            } catch (Throwable t) {
                Log.w(TAG, "poll error", t);
            }
            if (running && workerHandler != null) {
                workerHandler.postDelayed(this, 2000);
            }
        }
    };

    private void registerObserver() {
        if (observer != null) return;
        try {
            observer = new ContentObserver(workerHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    workerHandler.post(() -> {
                        try {
                            scanLatestScreenshot("observer");
                        } catch (Throwable t) {
                            Log.w(TAG, "observer error", t);
                        }
                    });
                }
            };
            getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            );
        } catch (Throwable t) {
            Log.w(TAG, "register observer failed", t);
            observer = null;
        }
    }

    private void unregisterObserver() {
        if (observer == null) return;
        try {
            getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {
        }
        observer = null;
    }

    private void scanLatestScreenshot(String source) {
        if (processing) return;
        if (TextUtils.isEmpty(accessKeyId) || TextUtils.isEmpty(accessKeySecret)) {
            Log.w(TAG, "OCR keys missing");
            return;
        }
        if (TextUtils.isEmpty(deepseekKey)) {
            Log.w(TAG, "DeepSeek key missing");
            return;
        }

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.RELATIVE_PATH
        };
        String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(collection, projection, null, null, sort);
            if (cursor == null || !cursor.moveToFirst()) return;

            long id = cursor.getLong(0);
            String name = cursor.getString(1);
            long dateAddedSec = cursor.getLong(2);
            String dataPath = projection.length > 3 ? cursor.getString(3) : null;
            String relative = projection.length > 4 ? cursor.getString(4) : null;

            long addedMs = dateAddedSec * 1000L;
            if (addedMs + 2000 < watchStartMs) return;
            if (id == lastHandledId) return;
            if (!isLikelyScreenshot(name, relative, dataPath)) return;

            lastHandledId = id;
            Uri itemUri = Uri.withAppendedPath(collection, String.valueOf(id));
            processing = true;
            updateFgs("正在识别截屏…");
            processUri(itemUri, dataPath, source);
        } catch (Throwable t) {
            Log.e(TAG, "scan failed", t);
            processing = false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean isLikelyScreenshot(String name, String relative, String path) {
        String blob = ((name == null ? "" : name) + " " +
            (relative == null ? "" : relative) + " " +
            (path == null ? "" : path)).toLowerCase(Locale.ROOT);
        return blob.contains("screenshot")
            || blob.contains("screen_shot")
            || blob.contains("截屏")
            || blob.contains("截图")
            || blob.contains("screenshots");
    }

    private void processUri(Uri uri, String dataPath, String source) {
        workerHandler.post(() -> {
            try {
                byte[] jpeg = readCompressedJpeg(uri, dataPath, 1280, 80);
                if (jpeg == null || jpeg.length < 100) {
                    notifyResult("未识别到", "图片读取失败");
                    return;
                }
                String ocrText = aliyunOcr(jpeg);
                if (TextUtils.isEmpty(ocrText)) {
                    notifyResult("未识别到", "OCR 无文字");
                    return;
                }
                String cleaned = cleanOcr(ocrText);
                JSONObject matched = matchQuestion(cleaned);
                JSONObject question = matched != null ? matched : buildFallbackQuestion(cleaned);
                String answer = askDeepSeek(question);
                if (TextUtils.isEmpty(answer)) {
                    notifyResult("未识别到", "DeepSeek 无答案");
                    return;
                }
                String display = formatAnswer(question, answer);
                notifyResult("答案 · " + trim(display, 40), display);
                SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
                sp.edit()
                    .putString("lastAnswer", display)
                    .putString("lastOcr", cleaned)
                    .putLong("lastAt", System.currentTimeMillis())
                    .apply();
                Log.i(TAG, "done source=" + source + " answer=" + display);
            } catch (Throwable t) {
                Log.e(TAG, "process failed", t);
                notifyResult("未识别到", safeMsg(t));
            } finally {
                processing = false;
                updateFgs("截屏搜题守护中");
            }
        });
    }

    private byte[] readCompressedJpeg(Uri uri, String dataPath, int maxSide, int quality) {
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = openImageStream(uri, dataPath);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            in = null;

            int sample = 1;
            int w = Math.max(1, bounds.outWidth);
            int h = Math.max(1, bounds.outHeight);
            while (Math.max(w / sample, h / sample) > maxSide * 2) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = openImageStream(uri, dataPath);
            if (in == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            in.close();
            in = null;
            if (bmp == null) return null;

            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            int longSide = Math.max(bw, bh);
            if (longSide > maxSide) {
                float scale = maxSide * 1f / longSide;
                Bitmap scaled = Bitmap.createScaledBitmap(
                    bmp,
                    Math.max(1, Math.round(bw * scale)),
                    Math.max(1, Math.round(bh * scale)),
                    true
                );
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            bmp.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "compress failed", t);
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private InputStream openImageStream(Uri uri, String dataPath) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in != null) return in;
        } catch (Throwable ignored) {
        }
        try {
            if (!TextUtils.isEmpty(dataPath)) {
                File f = new File(dataPath);
                if (f.exists()) return new FileInputStream(f);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String aliyunOcr(byte[] jpeg) throws Exception {
        String timestamp = iso8601();
        String nonce = UUID.randomUUID().toString();
        List<String[]> params = new ArrayList<>();
        params.add(new String[]{"AccessKeyId", accessKeyId});
        params.add(new String[]{"Action", "RecognizeGeneral"});
        params.add(new String[]{"Format", "JSON"});
        params.add(new String[]{"SignatureMethod", "HMAC-SHA1"});
        params.add(new String[]{"SignatureNonce", nonce});
        params.add(new String[]{"SignatureVersion", "1.0"});
        params.add(new String[]{"Timestamp", timestamp});
        params.add(new String[]{"Version", "2021-07-07"});
        Collections.sort(params, (a, b) -> a[0].compareTo(b[0]));

        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) canonical.append("&");
            canonical.append(percentEncode(params.get(i)[0]))
                .append("=")
                .append(percentEncode(params.get(i)[1]));
        }
        String stringToSign = "POST&" + percentEncode("/") + "&" + percentEncode(canonical.toString());
        String signature = hmacSha1Base64(stringToSign, accessKeySecret + "&");
        params.add(new String[]{"Signature", signature});
        Collections.sort(params, (a, b) -> a[0].compareTo(b[0]));

        StringBuilder query = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) query.append("&");
            query.append(percentEncode(params.get(i)[0]))
                .append("=")
                .append(percentEncode(params.get(i)[1]));
        }
        String url = "https://" + ocrEndpoint + "/?" + query;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        OutputStream os = conn.getOutputStream();
        os.write(jpeg);
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readStream(stream);
        conn.disconnect();
        if (code != 200) throw new RuntimeException("OCR HTTP " + code + ": " + trim(body, 180));
        JSONObject json = new JSONObject(body);
        if (json.has("Code") && !json.isNull("Code")) {
            throw new RuntimeException(json.optString("Message", json.optString("Code")));
        }
        Object data = json.opt("Data");
        if (data instanceof String) {
            try {
                data = new JSONObject((String) data);
            } catch (Throwable ignored) {
                return ((String) data).trim();
            }
        }
        if (data instanceof JSONObject) {
            JSONObject d = (JSONObject) data;
            if (d.has("content")) return d.optString("content", "").trim();
            JSONArray words = d.optJSONArray("prism_wordsInfo");
            if (words != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < words.length(); i++) {
                    JSONObject w = words.optJSONObject(i);
                    if (w == null) continue;
                    String word = w.optString("word", "");
                    if (!TextUtils.isEmpty(word)) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(word);
                    }
                }
                return sb.toString().trim();
            }
        }
        return "";
    }

    private String cleanOcr(String raw) {
        if (raw == null) return "";
        String[] lines = raw.replace("\r", "\n").split("\n+");
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            String t = line.replaceAll("\\s+", " ").trim();
            if (t.isEmpty() || t.length() <= 1) continue;
            if (t.matches("^\\d{1,2}:\\d{2}.*")) continue;
            if (t.matches("^(返回|提交|下一题|上一题|确定|取消|关闭)$")) continue;
            if (t.contains("按住") && t.contains("语音")) continue;
            if (body.length() > 0) body.append('\n');
            body.append(t);
        }
        return body.toString().trim();
    }

    private JSONObject matchQuestion(String cleaned) {
        try {
            String jsonText = readFileText(questionsPath);
            if (TextUtils.isEmpty(jsonText)) return null;
            JSONObject root = new JSONObject(jsonText);
            JSONArray arr = root.optJSONArray("questions");
            if (arr == null || arr.length() == 0) return null;
            String stem = extractStem(cleaned);
            String query = TextUtils.isEmpty(stem) ? cleaned : stem;
            double best = 0;
            JSONObject bestQ = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject q = arr.optJSONObject(i);
                if (q == null) continue;
                double score = score(query, q.optString("title", ""));
                if (score > best) {
                    best = score;
                    bestQ = q;
                }
            }
            if (bestQ != null && best >= matchThreshold) {
                JSONObject out = new JSONObject();
                out.put("title", bestQ.optString("title", ""));
                out.put("typeLabel", bestQ.optString("typeLabel", bestQ.optString("type", "题目")));
                out.put("options", bestQ.optJSONObject("options"));
                out.put("match", best);
                return out;
            }
        } catch (Throwable t) {
            Log.w(TAG, "match failed", t);
        }
        return null;
    }

    private JSONObject buildFallbackQuestion(String cleaned) throws Exception {
        JSONObject q = new JSONObject();
        q.put("title", cleaned);
        q.put("typeLabel", "截图识别");
        q.put("options", new JSONObject());
        return q;
    }

    private String extractStem(String cleaned) {
        StringBuilder sb = new StringBuilder();
        for (String line : cleaned.split("\n+")) {
            String t = line.trim();
            if (t.matches("^[A-Fa-f][\\.、．\\s].*")) continue;
            sb.append(t);
        }
        String s = sb.toString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private double score(String query, String title) {
        String qn = normalize(query);
        String tn = normalize(title);
        if (qn.isEmpty() || tn.isEmpty()) return 0;
        if (tn.contains(qn) && qn.length() >= 8) return 0.95;
        if (qn.contains(tn) && tn.length() >= 8) return 0.92;
        String head = tn.length() > 40 ? tn.substring(0, 40) : tn;
        if (head.length() >= 10 && qn.contains(head)) return 0.88;
        int hits = 0;
        int sampleLen = Math.min(60, qn.length());
        for (int i = 0; i < sampleLen; i++) {
            if (tn.indexOf(qn.charAt(i)) >= 0) hits++;
        }
        return sampleLen == 0 ? 0 : (hits * 1.0 / sampleLen) * 0.7;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[，。！？、；：\"\"''（）《》【】\\s?？,.!;:'\"()\\[\\]{}]", "").toLowerCase(Locale.ROOT);
    }

    private String askDeepSeek(JSONObject question) throws Exception {
        String typeLabel = question.optString("typeLabel", "题目");
        String title = question.optString("title", "");
        JSONObject options = question.optJSONObject("options");
        StringBuilder optionBlock = new StringBuilder();
        if (options != null) {
            List<String> keys = new ArrayList<>();
            Iterator<String> it = options.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            for (String k : keys) {
                optionBlock.append(k).append(". ").append(options.optString(k, "")).append('\n');
            }
        }
        if (optionBlock.length() == 0) optionBlock.append("（无选项，请直接给文字答案）");

        String prompt = "你是严谨的考试答题助手。根据题目作答，不要输出分析过程。\n"
            + "输出要求：\n"
            + "- 单选题：只输出一个选项字母，如 C\n"
            + "- 多选题：只输出字母组合（按字母序），如 ABD\n"
            + "- 填空/简答：只输出最终答案正文，尽量简短\n"
            + "- 不要输出「答案是」等前缀，不要解释\n\n"
            + "题型：" + typeLabel + "\n"
            + "题目：" + title + "\n"
            + "选项：\n" + optionBlock;

        JSONObject body = new JSONObject();
        body.put("model", deepseekModel);
        body.put("temperature", 0.2);
        body.put("max_tokens", 256);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", "你只输出考试答案本身，不输出分析。"));
        messages.put(new JSONObject().put("role", "user").put("content", prompt));
        body.put("messages", messages);

        String base = deepseekBase.endsWith("/") ? deepseekBase.substring(0, deepseekBase.length() - 1) : deepseekBase;
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(45000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + deepseekKey);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = readStream(stream);
        conn.disconnect();
        if (code != 200) throw new RuntimeException("DeepSeek HTTP " + code + ": " + trim(resp, 180));
        JSONObject json = new JSONObject(resp);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        String content = choices.getJSONObject(0).optJSONObject("message").optString("content", "").trim();
        return parseAnswer(content);
    }

    private String parseAnswer(String content) {
        String text = content == null ? "" : content.trim();
        text = text.replaceFirst("(?i)^答案[:：\\s]*", "").trim();
        if (text.matches("(?i)^[A-Fa-f]{2,6}$")) return text.toUpperCase(Locale.ROOT);
        if (text.length() <= 8 && text.matches(".*\\b([A-Fa-f])\\b.*")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([A-Fa-f])\\b").matcher(text);
            if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
        }
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private String formatAnswer(JSONObject question, String answer) {
        JSONObject options = question.optJSONObject("options");
        if (options != null && answer != null && answer.matches("(?i)^[A-Fa-f]+$")) {
            StringBuilder sb = new StringBuilder();
            for (char c : answer.toUpperCase(Locale.ROOT).toCharArray()) {
                String key = String.valueOf(c);
                if (sb.length() > 0) sb.append(" / ");
                String opt = options.optString(key, "");
                sb.append(TextUtils.isEmpty(opt) ? key : (key + ". " + opt));
            }
            return sb.toString();
        }
        return answer;
    }

    private void startAsForeground(String title, String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_GUARD,
                "截屏搜题守护",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持后台识图作答");
            manager.createNotificationChannel(channel);
        }
        Notification notification = buildOngoing(title, content);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_FGS, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_FGS, notification);
        }
    }

    private void updateFgs(String content) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.notify(NOTIFY_FGS, buildOngoing("截屏搜题守护中", content));
        } catch (Throwable ignored) {
        }
    }

    private Notification buildOngoing(String title, String content) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_GUARD)
            : new Notification.Builder(this);
        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private void notifyResult(String title, String content) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_RESULT,
                    "搜题结果悬浮提醒",
                    NotificationManager.IMPORTANCE_HIGH
                );
                channel.enableVibration(true);
                manager.createNotificationChannel(channel);
            }
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            PendingIntent pi = PendingIntent.getActivity(
                this,
                1,
                launchIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.FLAG_UPDATE_CURRENT
            );
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_RESULT)
                : new Notification.Builder(this);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setPriority(Notification.PRIORITY_HIGH);
            }
            Notification notification = builder
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
            manager.notify(NOTIFY_RESULT + (int) (System.currentTimeMillis() % 1000), notification);
        } catch (Throwable t) {
            Log.w(TAG, "notify result failed", t);
        }
    }

    private static String readFileText(String path) throws Exception {
        if (TextUtils.isEmpty(path)) return "";
        File f = new File(path);
        if (!f.exists()) return "";
        FileInputStream in = new FileInputStream(f);
        String text = readStream(in);
        in.close();
        return text;
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String iso8601() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private static String percentEncode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
    }

    private static String hmacSha1Base64(String message, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    private static String safe(String v, String def) {
        return TextUtils.isEmpty(v) ? def : v;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : trim(m, 60);
    }
}
