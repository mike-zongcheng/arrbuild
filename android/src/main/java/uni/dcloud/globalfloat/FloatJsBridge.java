package uni.dcloud.globalfloat;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.WebView;

import java.util.ArrayList;

public class FloatJsBridge {

    private final FloatWindowService service;
    private final WebView webView;
    private SpeechRecognizer speechRecognizer;

    FloatJsBridge(FloatWindowService service, WebView webView) {
        this.service = service;
        this.webView = webView;
    }

    @android.webkit.JavascriptInterface
    public void closeFloat() {
        service.getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                service.closeOverlay();
            }
        });
    }

    @android.webkit.JavascriptInterface
    public void startSpeech() {
        service.getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                startSpeechInternal();
            }
        });
    }

    private void startSpeechInternal() {
        if (!SpeechRecognizer.isRecognitionAvailable(service)) {
            notifySpeechError("当前设备不支持语音识别");
            return;
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(service);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override
            public void onError(int error) {
                notifySpeechError("语音识别失败，请重试");
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts != null && !texts.isEmpty()) {
                    notifySpeechResult(texts.get(0));
                } else {
                    notifySpeechError("未识别到内容");
                }
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        speechRecognizer.startListening(intent);
    }

    void destroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void notifySpeechError(final String message) {
        service.getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "window.onSpeechError && window.onSpeechError(" + JSONObjectUtil.quote(message) + ")",
                    null
                );
            }
        });
    }

    private void notifySpeechResult(final String text) {
        service.getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "window.onSpeechResult && window.onSpeechResult(" + JSONObjectUtil.quote(text) + ")",
                    null
                );
            }
        });
    }
}
