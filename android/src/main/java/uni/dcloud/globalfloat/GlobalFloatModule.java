package uni.dcloud.globalfloat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

public class GlobalFloatModule extends UniModule {

    @UniJSMethod(uiThread = false)
    public void checkOverlayPermission(UniJSCallback callback) {
        JSONObject result = new JSONObject();
        Activity activity = (Activity) mUniSDKInstance.getContext();
        boolean granted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            granted = Settings.canDrawOverlays(activity);
        }
        result.put("granted", granted);
        if (callback != null) {
            callback.invoke(result);
        }
    }

    @UniJSMethod(uiThread = true)
    public void requestOverlayPermission(UniJSCallback callback) {
        Activity activity = (Activity) mUniSDKInstance.getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(intent);
        }
        JSONObject result = new JSONObject();
        result.put("requested", true);
        if (callback != null) {
            callback.invoke(result);
        }
    }

    @UniJSMethod(uiThread = true)
    public void showFloat(JSONObject options, UniJSCallback callback) {
        Activity activity = (Activity) mUniSDKInstance.getContext();
        JSONObject result = new JSONObject();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            result.put("code", -1);
            result.put("message", "未授予悬浮窗权限");
            if (callback != null) {
                callback.invoke(result);
            }
            return;
        }

        String url = options != null ? options.getString("url") : null;
        Intent intent = new Intent(activity, FloatWindowService.class);
        if (url != null) {
            intent.putExtra("url", url);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent);
        } else {
            activity.startService(intent);
        }

        result.put("code", 0);
        result.put("message", "ok");
        if (callback != null) {
            callback.invoke(result);
        }
    }

    @UniJSMethod(uiThread = true)
    public void hideFloat(UniJSCallback callback) {
        Activity activity = (Activity) mUniSDKInstance.getContext();
        activity.stopService(new Intent(activity, FloatWindowService.class));
        JSONObject result = new JSONObject();
        result.put("code", 0);
        result.put("message", "ok");
        if (callback != null) {
            callback.invoke(result);
        }
    }

    @UniJSMethod(uiThread = false)
    public void isShowing(UniJSCallback callback) {
        JSONObject result = new JSONObject();
        result.put("showing", FloatWindowService.isRunning());
        if (callback != null) {
            callback.invoke(result);
        }
    }

    /**
     * 供 JS 确认基座是否已含 OCR直答 管道（旧基座无此方法或 version 对不上）
     */
    @UniJSMethod(uiThread = false)
    public void getPipelineInfo(UniJSCallback callback) {
        JSONObject result = new JSONObject();
        result.put("pipeline", ScreenshotGuardService.PIPELINE);
        result.put("version", ScreenshotGuardService.PIPELINE_VERSION);
        result.put("ocrDirect", true);
        result.put("guardRunning", ScreenshotGuardService.isRunning());
        if (callback != null) {
            callback.invoke(result);
        }
    }

    @UniJSMethod(uiThread = true)
    public void startScreenshotGuard(JSONObject options, UniJSCallback callback) {
        Activity activity = (Activity) mUniSDKInstance.getContext();
        JSONObject result = new JSONObject();
        try {
            Intent intent = new Intent(activity, ScreenshotGuardService.class);
            if (options != null) {
                putExtra(intent, options, "accessKeyId");
                putExtra(intent, options, "accessKeySecret");
                putExtra(intent, options, "ocrEndpoint");
                putExtra(intent, options, "deepseekKey");
                putExtra(intent, options, "deepseekBase");
                putExtra(intent, options, "deepseekModel");
                putExtra(intent, options, "questionsPath");
                putExtra(intent, options, "pipeline");
                putExtra(intent, options, "pipelineVersion");
                if (options.containsKey("matchThreshold")) {
                    intent.putExtra("matchThreshold", options.getDoubleValue("matchThreshold"));
                }
            }
            // 固定声明当前管道，便于日志辨认
            intent.putExtra("pipeline", ScreenshotGuardService.PIPELINE);
            intent.putExtra("pipelineVersion", ScreenshotGuardService.PIPELINE_VERSION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent);
            } else {
                activity.startService(intent);
            }
            result.put("code", 0);
            result.put("message", "ok");
            result.put("via", "ScreenshotGuardService");
            result.put("pipeline", ScreenshotGuardService.PIPELINE);
            result.put("version", ScreenshotGuardService.PIPELINE_VERSION);
        } catch (Throwable t) {
            result.put("code", -1);
            result.put("message", t.getMessage() == null ? "start failed" : t.getMessage());
        }
        if (callback != null) {
            callback.invoke(result);
        }
    }

    @UniJSMethod(uiThread = true)
    public void stopScreenshotGuard(UniJSCallback callback) {
        Activity activity = (Activity) mUniSDKInstance.getContext();
        JSONObject result = new JSONObject();
        try {
            // 先发 stop action（START_NOT_STICKY + stopSelf），再 stopService
            Intent stopIntent = new Intent(activity, ScreenshotGuardService.class);
            stopIntent.putExtra("action", "stop");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(stopIntent);
                } else {
                    activity.startService(stopIntent);
                }
            } catch (Throwable ignored) {
            }
            activity.stopService(new Intent(activity, ScreenshotGuardService.class));
            result.put("code", 0);
            result.put("message", "ok");
            result.put("running", ScreenshotGuardService.isRunning());
            result.put("version", ScreenshotGuardService.PIPELINE_VERSION);
        } catch (Throwable t) {
            result.put("code", -1);
            result.put("message", t.getMessage() == null ? "stop failed" : t.getMessage());
        }
        if (callback != null) {
            callback.invoke(result);
        }
    }

    @UniJSMethod(uiThread = false)
    public void isScreenshotGuardRunning(UniJSCallback callback) {
        JSONObject result = new JSONObject();
        result.put("running", ScreenshotGuardService.isRunning());
        result.put("version", ScreenshotGuardService.PIPELINE_VERSION);
        result.put("pipeline", ScreenshotGuardService.PIPELINE);
        if (callback != null) {
            callback.invoke(result);
        }
    }

    private void putExtra(Intent intent, JSONObject options, String key) {
        if (options != null && options.containsKey(key)) {
            String v = options.getString(key);
            if (v != null) intent.putExtra(key, v);
        }
    }
}
