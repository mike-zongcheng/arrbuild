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
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * 前台服务（v1.3）：后台扫截屏 → 阿里云 OCR → OCR原文直喂 DeepSeek → 系统悬浮窗
 * 不再依赖题库题干匹配（同标题多套选项会串题）。
 * 不依赖 UniApp WebView JS（单屏后台 JS 会被 pauseTimers）。
 */
public class ScreenshotGuardService extends Service {
    private static final String TAG = "ScreenshotGuard";
    /** 与 package.json / JS pipelineVersion 对齐，浮层可辨认新基座 */
    public static final String PIPELINE_VERSION = "1.3.0";
    public static final String PIPELINE = "ocr_direct";
    private static final String PREF = "zuobi_screenshot_guard";
    private static final String CHANNEL_GUARD = "zuobi_guard_fgs";
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

    private WindowManager answerWindowManager;
    private View answerOverlay;
    private Runnable answerDismissRunnable;

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
        startAsForeground("截屏搜题·OCR直答", "v" + PIPELINE_VERSION + " 后台识图作答中");
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
        return START_NOT_STICKY;
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
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler.post(this::hideAnswerOverlay);
        }
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
                // 主路径：OCR 原文直喂 DeepSeek（自行剔除界面污染），不再依赖题库题干匹配
                String answer = askDeepSeekFromOcr(ocrText);
                if (TextUtils.isEmpty(answer)) {
                    notifyResult("未识别到", "DeepSeek 无答案");
                    return;
                }
                String display = answer;
                String overlayTitle = "OCR直答·v" + PIPELINE_VERSION;
                notifyResult(overlayTitle + " · " + trim(display, 28), display, overlayTitle);
                SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
                sp.edit()
                    .putString("lastAnswer", display)
                    .putString("lastOcr", ocrText)
                    .putString("pipeline", PIPELINE)
                    .putString("pipelineVersion", PIPELINE_VERSION)
                    .putLong("lastAt", System.currentTimeMillis())
                    .apply();
                Log.i(TAG, "done source=" + source + " pipeline=" + PIPELINE
                    + " v=" + PIPELINE_VERSION + " answer=" + display);
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
        String[] parts = raw.replace("\r", "\n").split("\n+");
        List<String> lines = new ArrayList<>();
        for (String line : parts) {
            String t = line.replaceAll("\\s+", " ").trim();
            if (isNoiseLine(t)) continue;
            lines.add(t);
        }
        if (lines.isEmpty()) return "";

        // 找最佳选项簇
        List<int[]> hits = new ArrayList<>(); // [index]
        List<String> keys = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([A-Fa-f])[\\.、．\\s)）]+(.+)$")
                .matcher(lines.get(i));
            if (m.find()) {
                hits.add(new int[]{i});
                keys.add(m.group(1).toUpperCase(Locale.ROOT));
                texts.add(m.group(2).trim());
            }
        }

        int clusterStart = -1;
        int clusterEnd = -1;
        if (!hits.isEmpty()) {
            // 简单取含 A 且选项最多的连续簇
            int bestScore = -1;
            int i = 0;
            while (i < hits.size()) {
                int j = i;
                while (j + 1 < hits.size() && hits.get(j + 1)[0] - hits.get(j)[0] <= 2) j++;
                int score = (j - i + 1) * 10;
                boolean hasA = false;
                for (int k = i; k <= j; k++) {
                    if ("A".equals(keys.get(k))) hasA = true;
                }
                if (hasA) score += 8;
                if (score > bestScore) {
                    bestScore = score;
                    clusterStart = i;
                    clusterEnd = j;
                }
                i = j + 1;
            }
        }

        StringBuilder stem = new StringBuilder();
        JSONObject options = new JSONObject();
        try {
            if (clusterStart >= 0) {
                int firstOptLine = hits.get(clusterStart)[0];
                List<String> stemLines = new ArrayList<>();
                for (int i = firstOptLine - 1; i >= 0 && stemLines.size() < 10; i--) {
                    String t = lines.get(i);
                    if (t.matches("^[A-Fa-f][\\.、．\\s)）].*")) break;
                    stemLines.add(0, t);
                }
                for (String t : stemLines) {
                    if (stem.length() > 0) stem.append('\n');
                    stem.append(t);
                }
                for (int k = clusterStart; k <= clusterEnd; k++) {
                    if (!options.has(keys.get(k))) options.put(keys.get(k), texts.get(k));
                }
            } else {
                // 填空/简答：围绕空白符或最长题干行取块，不要整屏杂字
                int center = 0;
                int bestScore = -1;
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i);
                    int s = Math.min(t.length(), 80);
                    if (looksLikeBlankText(t)) s += 40;
                    if (looksLikeShortText(t)) s += 25;
                    if (t.endsWith("？") || t.endsWith("?") || t.contains("根据") || t.contains("规定")) s += 15;
                    if (t.length() < 6) s -= 20;
                    if (s > bestScore) {
                        bestScore = s;
                        center = i;
                    }
                }
                int from = center;
                int to = center;
                while (from > 0 && (center - from + 1) < 12) {
                    from--;
                    if (lines.get(from).matches("^[A-Fa-f][\\.、．\\s)）].*")) {
                        from++;
                        break;
                    }
                }
                while (to + 1 < lines.size() && (to - from + 1) < 14) {
                    String next = lines.get(to + 1);
                    if (next.matches("^[A-Fa-f][\\.、．\\s)）].*")) break;
                    if (next.length() <= 4 && !looksLikeBlankText(next)) break;
                    to++;
                }
                for (int i = from; i <= to; i++) {
                    if (stem.length() > 0) stem.append('\n');
                    stem.append(lines.get(i));
                }
            }
        } catch (Throwable ignored) {
        }

        StringBuilder out = new StringBuilder(stem.toString().trim());
        try {
            List<String> oks = new ArrayList<>();
            Iterator<String> it = options.keys();
            while (it.hasNext()) oks.add(it.next());
            Collections.sort(oks);
            for (String k : oks) {
                if (out.length() > 0) out.append('\n');
                out.append(k).append(". ").append(options.optString(k, ""));
            }
        } catch (Throwable ignored) {
        }
        return out.toString().trim();
    }

    private boolean isNoiseLine(String t) {
        if (t == null || t.isEmpty() || t.length() <= 1) return true;
        if (t.matches("^\\d{1,2}:\\d{2}.*")) return true;
        if (t.matches("^(返回|提交|下一题|上一题|确定|取消|关闭|交卷|暂存|分享|收藏)$")) return true;
        if (t.matches("^(首页|我的|设置|消息|题库|练习|考试|答题卡|标记)$")) return true;
        if (t.matches("^第?\\s*\\d+\\s*[/／]\\s*\\d+\\s*题?$")) return true;
        if (t.startsWith("倒计时") || t.startsWith("剩余时间") || t.startsWith("已用时")) return true;
        if (t.contains("按住") && t.contains("语音")) return true;
        if (t.contains("AI") && t.contains("匹配")) return true;
        return false;
    }

    private boolean looksLikeBlankText(String t) {
        if (t == null) return false;
        return t.matches(".*(_{2,}|…{2,}|\\.{4,}|（\\s*）|\\(\\s*\\)|【\\s*】|＿{2,}|空白处|填入|填写|填空).*");
    }

    private boolean looksLikeShortText(String t) {
        if (t == null) return false;
        return t.contains("简答") || t.contains("简述") || t.contains("论述") || t.contains("请说明") || t.contains("请分析");
    }

    private void applyOpenType(JSONObject q, String text) throws Exception {
        if (looksLikeBlankText(text)) {
            q.put("type", "fill_blank");
            q.put("typeLabel", "考试填空");
        } else if (looksLikeShortText(text)) {
            q.put("type", "short_answer");
            q.put("typeLabel", "考试简答");
        } else {
            q.put("type", "fill_blank");
            q.put("typeLabel", "考试填空");
        }
    }

    private JSONObject extractOptions(String cleaned) {
        JSONObject options = new JSONObject();
        if (cleaned == null) return options;
        try {
            for (String line : cleaned.split("\n+")) {
                String t = line.trim();
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([A-Fa-f])[\\.、．\\s)）︰:]+(.+)$")
                    .matcher(t);
                if (m.find()) {
                    options.put(m.group(1).toUpperCase(Locale.ROOT), m.group(2).trim());
                    continue;
                }
                m = java.util.regex.Pattern.compile("^([A-Fa-f])([\\u4e00-\\u9fff].+)$").matcher(t);
                if (m.find()) {
                    options.put(m.group(1).toUpperCase(Locale.ROOT), m.group(2).trim());
                }
            }
            // 同一行挤在一起：A、xx B、yy
            if (options.length() < 2) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:^|[\\s\\n])([A-Fa-f])[\\.、．\\s)）︰:]+([^A-Fa-f\\n]{2,}?)(?=(?:\\s*[A-Fa-f][\\.、．\\s)）︰:])|$)")
                    .matcher(" " + cleaned);
                while (m.find()) {
                    String k = m.group(1).toUpperCase(Locale.ROOT);
                    String v = m.group(2).replaceAll("\\s+", " ").trim();
                    if (!v.isEmpty() && !options.has(k)) options.put(k, v);
                }
            }
        } catch (Throwable ignored) {
        }
        return options;
    }

    private JSONObject buildQuestionForAi(String cleaned, String stem, JSONObject ocrOptions, JSONObject matched) throws Exception {
        if (matched != null) return matched;
        return buildFallbackQuestion(cleaned);
    }

    /**
     * 模糊选项指纹 + 题干辅助；门槛放宽，避免 OCR 差一点就全未命中。
     */
    private JSONObject matchQuestion(String stem, JSONObject ocrOptions) {
        try {
            String jsonText = readFileText(questionsPath);
            if (TextUtils.isEmpty(jsonText)) return null;
            JSONObject root = new JSONObject(jsonText);
            JSONArray arr = root.optJSONArray("questions");
            if (arr == null || arr.length() == 0) return null;

            boolean hasOcrOpts = ocrOptions != null && ocrOptions.length() >= 2;
            String query = TextUtils.isEmpty(stem) ? "" : stem;
            final double OPT_LOCK = 0.42;
            final double OPT_ACCEPT = 0.35;

            class Cand {
                JSONObject q;
                double title;
                double opt;
                double score;
            }
            List<Cand> all = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject q = arr.optJSONObject(i);
                if (q == null) continue;
                Cand c = new Cand();
                c.q = q;
                c.title = scoreTitle(query, q.optString("title", ""));
                c.opt = scoreOptions(ocrOptions, q.optJSONObject("options"));
                c.score = combineScore(c.title, c.opt, hasOcrOpts);
                all.add(c);
            }

            JSONObject chosen = null;
            double chosenOpt = 0;
            double chosenScore = 0;

            if (hasOcrOpts) {
                List<Cand> pool = new ArrayList<>();
                for (Cand c : all) {
                    if (c.title >= 0.45 || c.opt >= OPT_ACCEPT) pool.add(c);
                }
                if (pool.isEmpty()) {
                    for (Cand c : all) {
                        if (c.title >= 0.3) pool.add(c);
                    }
                }
                Collections.sort(pool, (a, b) -> {
                    if (Math.abs(b.opt - a.opt) > 0.04) return Double.compare(b.opt, a.opt);
                    return Double.compare(b.title, a.title);
                });
                if (pool.isEmpty()) return null;

                Cand best = pool.get(0);
                // 同标题消歧
                String titleKey = normalize(best.q.optString("title", ""));
                if (titleKey.length() > 36) titleKey = titleKey.substring(0, 36);
                List<Cand> same = new ArrayList<>();
                for (Cand c : pool) {
                    String t = normalize(c.q.optString("title", ""));
                    if (t.length() > 36) t = t.substring(0, 36);
                    if (t.equals(titleKey)) same.add(c);
                }
                if (same.size() > 1) {
                    Collections.sort(same, (a, b) -> Double.compare(b.opt, a.opt));
                    Cand top = same.get(0);
                    Cand second = same.get(1);
                    if (top.opt >= OPT_ACCEPT || (top.opt - second.opt) >= 0.08) {
                        chosen = top.q;
                        chosenOpt = top.opt;
                        chosenScore = top.score;
                    } else if (top.title >= 0.7 && top.opt >= 0.25) {
                        chosen = top.q;
                        chosenOpt = top.opt;
                        chosenScore = top.score;
                    }
                } else if (best.opt >= OPT_ACCEPT || best.title >= 0.7) {
                    chosen = best.q;
                    chosenOpt = best.opt;
                    chosenScore = best.score;
                } else if (best.opt >= OPT_LOCK * 0.8 && best.title >= 0.5) {
                    chosen = best.q;
                    chosenOpt = best.opt;
                    chosenScore = best.score;
                }
            } else {
                Cand best = null;
                for (Cand c : all) {
                    if (best == null || c.score > best.score) best = c;
                }
                if (best == null || best.score < matchThreshold) return null;
                String titleKey = normalize(best.q.optString("title", ""));
                if (titleKey.length() > 36) titleKey = titleKey.substring(0, 36);
                int same = 0;
                for (Cand c : all) {
                    String t = normalize(c.q.optString("title", ""));
                    if (t.length() > 36) t = t.substring(0, 36);
                    if (t.equals(titleKey)) same++;
                }
                if (same > 1) {
                    Log.w(TAG, "ambiguous same title count=" + same);
                    return null;
                }
                chosen = best.q;
                chosenOpt = best.opt;
                chosenScore = best.score;
            }

            if (chosen == null) return null;

            JSONObject out = new JSONObject();
            out.put("id", chosen.optLong("id", 0));
            out.put("title", chosen.optString("title", ""));
            out.put("type", chosen.optString("type", ""));
            out.put("typeLabel", chosen.optString("typeLabel", chosen.optString("type", "题目")));
            out.put("options", chosen.optJSONObject("options") != null
                ? chosen.optJSONObject("options") : new JSONObject());
            out.put("match", chosenScore);
            out.put("optionScore", chosenOpt);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "match failed", t);
        }
        return null;
    }

    private JSONObject buildFallbackQuestion(String cleaned) throws Exception {
        JSONObject q = new JSONObject();
        q.put("title", cleaned);
        q.put("options", new JSONObject());
        applyOpenType(q, cleaned);
        return q;
    }

    private String extractStem(String cleaned) {
        StringBuilder sb = new StringBuilder();
        for (String line : cleaned.split("\n+")) {
            String t = line.trim();
            if (t.matches("^[A-Fa-f][\\.、．\\s)）].*")) continue;
            sb.append(t);
        }
        String s = sb.toString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private double textSimilarity(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        if (x.equals(y)) return 1;
        if (x.contains(y) || y.contains(x)) return 0.95;
        int head = Math.min(6, Math.min(x.length(), y.length()));
        int headHit = 0;
        for (int i = 0; i < head; i++) {
            if (x.charAt(i) == y.charAt(i)) headHit++;
        }
        double headScore = head == 0 ? 0 : headHit * 1.0 / head;
        int gramHits = 0;
        int gramTotal = 0;
        String sample = x.length() > 36 ? x.substring(0, 36) : x;
        for (int i = 0; i + 1 < sample.length(); i++) {
            gramTotal++;
            String g = sample.substring(i, i + 2);
            if (y.contains(g)) gramHits++;
        }
        double gramScore = gramTotal == 0 ? 0 : gramHits * 1.0 / gramTotal;
        if (Math.min(x.length(), y.length()) <= 10) {
            return Math.max(headScore, gramScore * 0.85);
        }
        return gramScore * 0.7 + headScore * 0.3;
    }

    private double scoreOptionsAligned(JSONObject ocrOptions, JSONObject questionOptions) {
        if (ocrOptions == null || questionOptions == null || ocrOptions.length() == 0) return 0;
        try {
            double sum = 0;
            int total = 0;
            Iterator<String> it = ocrOptions.keys();
            while (it.hasNext()) {
                String k = it.next();
                String a = ocrOptions.optString(k, "");
                if (a.isEmpty()) continue;
                total++;
                sum += textSimilarity(a, questionOptions.optString(k, ""));
            }
            return total == 0 ? 0 : sum / total;
        } catch (Throwable t) {
            return 0;
        }
    }

    private double scoreOptionsBag(JSONObject ocrOptions, JSONObject questionOptions) {
        if (ocrOptions == null || questionOptions == null) return 0;
        try {
            List<String> a = new ArrayList<>();
            List<String> b = new ArrayList<>();
            Iterator<String> it = ocrOptions.keys();
            while (it.hasNext()) {
                String n = ocrOptions.optString(it.next(), "").trim();
                if (!n.isEmpty()) a.add(n);
            }
            Iterator<String> it2 = questionOptions.keys();
            while (it2.hasNext()) {
                String n = questionOptions.optString(it2.next(), "").trim();
                if (!n.isEmpty()) b.add(n);
            }
            if (a.isEmpty() || b.isEmpty()) return 0;
            double sum = 0;
            for (String x : a) {
                double best = 0;
                for (String y : b) best = Math.max(best, textSimilarity(x, y));
                sum += best;
            }
            return sum / Math.max(a.size(), b.size());
        } catch (Throwable t) {
            return 0;
        }
    }

    private double scoreOptions(JSONObject ocrOptions, JSONObject questionOptions) {
        return Math.max(scoreOptionsAligned(ocrOptions, questionOptions),
            scoreOptionsBag(ocrOptions, questionOptions));
    }

    private double scoreTitle(String query, String title) {
        String qn = normalize(query);
        String tn = normalize(title);
        if (qn.isEmpty() || tn.isEmpty()) return 0;
        if (tn.contains(qn) && qn.length() >= 8) return 0.95;
        if (qn.contains(tn) && tn.length() >= 8) return 0.92;
        String head = tn.length() > 48 ? tn.substring(0, 48) : tn;
        if (head.length() >= 10 && qn.contains(head)) return 0.88;
        int hits = 0;
        int sampleLen = Math.min(80, qn.length());
        for (int i = 0; i < sampleLen; i++) {
            if (tn.indexOf(qn.charAt(i)) >= 0) hits++;
        }
        return sampleLen == 0 ? 0 : (hits * 1.0 / sampleLen) * 0.7;
    }

    private double combineScore(double titleScore, double optScore, boolean hasOcrOpts) {
        if (!hasOcrOpts) return titleScore;
        if (optScore >= 0.9) return 0.99;
        if (optScore >= 0.42) return 0.45 * titleScore + 0.55 * optScore + 0.25;
        if (optScore >= 0.35) return titleScore * 0.35 + optScore * 0.65;
        return titleScore * 0.55 + optScore * 0.25;
    }

    private double score(String query, String title, JSONObject ocrOptions, JSONObject questionOptions) {
        return combineScore(scoreTitle(query, title), scoreOptions(ocrOptions, questionOptions),
            ocrOptions != null && ocrOptions.length() >= 2);
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[，。！？、；：\"\"''（）《》【】\\s?？,.!;:'\"()\\[\\]{}＿_…—－·]", "").toLowerCase(Locale.ROOT);
    }

    private String askDeepSeekFromOcr(String ocrText) throws Exception {
        String prompt = "你是严谨的中国证券监管/并购重组考试答题助手。\n"
            + "下面是截图 OCR 原文，可能混有界面杂质，例如：\n"
            + "页码、微信、倒计时、大纲、上一页/下一页、收藏、删除、更多、AI匹配格式、复制、状态栏时间等。\n"
            + "请自行识别真正题干与选项（或填空/简答），忽略无关污染文字，不要臆造不存在的条文。\n\n"
            + "输出要求：\n"
            + "- 单选题：只输出一个选项字母，如 C\n"
            + "- 多选题：只输出全部正确选项字母（按字母序），如 AD\n"
            + "- 填空/简答：只输出最终答案正文，尽量简短\n"
            + "- 不要输出「答案是」等前缀，不要解释\n\n"
            + "多选题判断提示：\n"
            + "- 先排除明显违法/不合规干扰项，例如：仅需董事长个人决定、重组完成后再披露、无需审议、无需信息披露、不需要聘机构等\n"
            + "- 优先选择符合监管常识的表述，例如：应当符合产业政策、现金购买不适用发股审核程序、真实准确完整披露、履行审议与披露义务等\n"
            + "- 有几个选几个，不要勉强凑数\n\n"
            + "OCR原文：\n" + ocrText;

        JSONObject body = new JSONObject();
        body.put("model", deepseekModel);
        body.put("temperature", 0.1);
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
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = conn.getOutputStream();
        os.write(bytes);
        os.close();

        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String resp = readStream(in);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("DeepSeek HTTP " + code + ": " + trim(resp, 200));
        }
        JSONObject json = new JSONObject(resp);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        String content = choices.getJSONObject(0).optJSONObject("message").optString("content", "").trim();
        return parseAnswerFromOcr(content);
    }

    private String parseAnswerFromOcr(String content) {
        String text = content == null ? "" : content.trim();
        text = text.replaceFirst("(?i)^答案[:：\\s]*", "").trim();
        String compact = text.replaceAll("\\s+", "");
        if (compact.matches("(?i)^[A-Fa-f]{2,6}$")) {
            return compact.toUpperCase(Locale.ROOT);
        }
        if (text.matches("(?i)^[A-Fa-f]{2,6}$")) return text.toUpperCase(Locale.ROOT);
        if (text.length() <= 12) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([A-Fa-f]{1,6})\\b").matcher(text);
            if (m.find()) {
                String g = m.group(1).toUpperCase(Locale.ROOT);
                char[] arr = g.toCharArray();
                java.util.Arrays.sort(arr);
                StringBuilder sb = new StringBuilder();
                for (char c : arr) {
                    if (sb.indexOf(String.valueOf(c)) < 0) sb.append(c);
                }
                return sb.toString();
            }
        }
        return text.length() > 120 ? text.substring(0, 120) : text;
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
        boolean isMulti = typeLabel.contains("多选") || "multiple_choice".equals(question.optString("type"));
        boolean isOpen = options == null || options.length() == 0
            || typeLabel.contains("填空") || typeLabel.contains("简答")
            || "fill_blank".equals(question.optString("type"))
            || "short_answer".equals(question.optString("type"));

        String prompt = "你是严谨的中国证券监管/并购重组考试答题助手。\n"
            + "必须依据题干与选项本身判断，不要臆造不存在的条文。\n"
            + "忽略截图里可能混入的界面文字（返回、交卷、倒计时、题号、工具栏等），只答当前这一道题。\n"
            + "输出要求：\n"
            + "- 单选题：只输出一个选项字母，如 C\n"
            + "- 多选题：只输出全部正确选项字母（按字母序），如 AD\n"
            + "- 填空题：只输出应填入空白处的词语/数字/短句，不要展开解释\n"
            + "- 简答题：只输出要点正文，尽量简短\n"
            + "- 不要输出「答案是」等前缀，不要解释\n\n"
            + (isMulti
                ? ("多选题判断提示：\n"
                + "- 先排除明显违法/不合规干扰项，例如：仅需董事长个人决定、重组完成后再披露、无需审议、无需信息披露、不需要聘机构等\n"
                + "- 优先选择符合监管常识的表述，例如：应当符合产业政策、现金购买不适用发股审核程序、真实准确完整披露、履行审议与披露义务等\n"
                + "- 有几个选几个，不要勉强凑数\n\n")
                : "")
            + (isOpen
                ? ("填空/简答提示：\n"
                + "- 题干里的 ______ / （） / 空白 即为作答位置\n"
                + "- 优先给出可直接填入的标准表述（期限、比例、机构名称、条文关键词等）\n\n")
                : "")
            + "题型：" + typeLabel + "\n"
            + "题目（来自题库原文，请严格按此题干与选项作答）：" + title + "\n"
            + "选项：\n" + optionBlock;

        JSONObject body = new JSONObject();
        body.put("model", deepseekModel);
        body.put("temperature", 0.1);
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
        return parseAnswer(content, question);
    }

    private String parseAnswer(String content, JSONObject question) {
        String text = content == null ? "" : content.trim();
        text = text.replaceFirst("(?i)^答案[:：\\s]*", "").trim();
        String typeLabel = question == null ? "" : question.optString("typeLabel", "");
        String type = question == null ? "" : question.optString("type", "");
        JSONObject options = question == null ? null : question.optJSONObject("options");
        boolean isOpen = options == null || options.length() == 0
            || typeLabel.contains("填空") || typeLabel.contains("简答")
            || "fill_blank".equals(type) || "short_answer".equals(type);
        if (isOpen) {
            return text.length() > 200 ? text.substring(0, 200) : text;
        }
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
        notifyResult(title, content, title);
    }

    private void notifyResult(String title, String content, String overlayTitle) {
        // 主线程弹真正的系统悬浮窗（覆盖在其它 App 上），不是通知栏普通弹框
        if (mainHandler != null) {
            final String t = overlayTitle == null ? (title == null ? "" : title) : overlayTitle;
            final String c = content == null ? "" : content;
            mainHandler.post(() -> showAnswerOverlay(t, c));
        }
        // 顺带发一条低调通知，方便回看；不抢悬浮样式
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    "zuobi_guard_result_quiet",
                    "搜题结果记录",
                    NotificationManager.IMPORTANCE_DEFAULT
                );
                manager.createNotificationChannel(channel);
            }
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(this, 1902, launchIntent, piFlags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, "zuobi_guard_result_quiet")
                : new Notification.Builder(this);
            Notification notification = builder
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build();
            manager.notify(NOTIFY_RESULT + (int) (System.currentTimeMillis() % 900), notification);
        } catch (Throwable t) {
            Log.w(TAG, "quiet notify failed", t);
        }
    }

    private void showAnswerOverlay(String title, String content) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.w(TAG, "无悬浮窗权限，无法显示答案悬浮框");
                // 尝试引导授权
                try {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                    );
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Throwable ignored) {
                }
                return;
            }

            hideAnswerOverlay();

            answerWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(0xEE0B1020);
            int pad = dp(16);
            panel.setPadding(pad, pad, pad, pad);

            TextView titleView = new TextView(this);
            titleView.setText(TextUtils.isEmpty(title) ? "答案" : title);
            titleView.setTextColor(0xFF94A3B8);
            titleView.setTextSize(13);
            panel.addView(titleView);

            TextView answerView = new TextView(this);
            answerView.setText(content);
            answerView.setTextColor(0xFF4ADE80);
            answerView.setTextSize(22);
            answerView.setPadding(0, dp(8), 0, dp(4));
            panel.addView(answerView);

            TextView tipView = new TextView(this);
            tipView.setText("点击关闭 · 8 秒后自动消失");
            tipView.setTextColor(0xFF64748B);
            tipView.setTextSize(11);
            panel.addView(tipView);

            panel.setOnClickListener(v -> hideAnswerOverlay());

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(48);
            lp.x = 0;
            int margin = dp(12);
            lp.width = getResources().getDisplayMetrics().widthPixels - margin * 2;

            answerOverlay = panel;
            answerWindowManager.addView(panel, lp);

            if (answerDismissRunnable != null) {
                mainHandler.removeCallbacks(answerDismissRunnable);
            }
            answerDismissRunnable = this::hideAnswerOverlay;
            mainHandler.postDelayed(answerDismissRunnable, 8000);
        } catch (Throwable t) {
            Log.e(TAG, "showAnswerOverlay failed", t);
        }
    }

    private void hideAnswerOverlay() {
        try {
            if (answerDismissRunnable != null && mainHandler != null) {
                mainHandler.removeCallbacks(answerDismissRunnable);
                answerDismissRunnable = null;
            }
            if (answerWindowManager != null && answerOverlay != null) {
                answerWindowManager.removeView(answerOverlay);
            }
        } catch (Throwable ignored) {
        } finally {
            answerOverlay = null;
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
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
