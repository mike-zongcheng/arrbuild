package uni.dcloud.globalfloat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

public class FloatWindowService extends Service {

    private static boolean running = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private FrameLayout overlayRoot;
    private WebView webView;
    private FloatJsBridge jsBridge;
    private WindowManager.LayoutParams layoutParams;

    private float lastX;
    private float lastY;
    private int initialLeft;
    private int initialTop;

    public static boolean isRunning() {
        return running;
    }

    Handler getMainHandler() {
        return mainHandler;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent != null ? intent.getStringExtra("url") : null;
        if (overlayRoot == null) {
            showOverlay(url);
        } else if (url != null && !url.isEmpty() && webView != null) {
            webView.loadUrl(url);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        String channelId = "global_float_channel";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "语音搜题悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持全局悬浮窗运行");
            manager.createNotificationChannel(channel);
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, channelId)
            : new Notification.Builder(this);

        Notification notification = builder
            .setContentTitle("语音搜题助手")
            .setContentText("全局悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1001, notification);
        }
    }

    private void showOverlay(String pageUrl) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayRoot = new FrameLayout(this);
        overlayRoot.setBackgroundColor(Color.TRANSPARENT);

        TextView dragBar = new TextView(this);
        dragBar.setText("按住拖动 · 全局悬浮搜题");
        dragBar.setTextColor(Color.WHITE);
        dragBar.setTextSize(12);
        dragBar.setGravity(Gravity.CENTER);
        dragBar.setBackgroundColor(0x66000000);
        dragBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        dragBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleDrag(event);
            }
        });

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        jsBridge = new FloatJsBridge(this, webView);
        webView.addJavascriptInterface(jsBridge, "AndroidBridge");

        String url = pageUrl;
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException("float page url is required");
        }
        webView.loadUrl(url);

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(36)
        );
        overlayRoot.addView(dragBar, barParams);

        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        webParams.topMargin = dp(36);
        overlayRoot.addView(webView, webParams);

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        layoutParams = new WindowManager.LayoutParams(
            dp(340),
            dp(520),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = dp(16);
        layoutParams.y = dp(80);

        windowManager.addView(overlayRoot, layoutParams);
    }

    private boolean handleDrag(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getRawX();
                lastY = event.getRawY();
                initialLeft = layoutParams.x;
                initialTop = layoutParams.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                layoutParams.x = initialLeft + (int) (event.getRawX() - lastX);
                layoutParams.y = initialTop + (int) (event.getRawY() - lastY);
                windowManager.updateViewLayout(overlayRoot, layoutParams);
                return true;
            default:
                return false;
        }
    }

    private void removeOverlay() {
        if (jsBridge != null) {
            jsBridge.destroy();
            jsBridge = null;
        }
        if (windowManager != null && overlayRoot != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) {
            }
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        overlayRoot = null;
    }

    void closeOverlay() {
        stopSelf();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
