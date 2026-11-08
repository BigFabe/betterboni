package app.betterboni;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;

import java.util.Locale;

final class MenuSpeaker implements AutoCloseable {
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech engine;
    private String pendingText;
    private boolean ready, closed;
    private int generation;

    MenuSpeaker(Activity activity) { this.activity = activity; }

    void speak(String originalTitle) {
        if (closed || originalTitle.trim().isEmpty()) return;
        pendingText = originalTitle.trim();
        if (engine != null) {
            if (ready) speakPending();
            return;
        }
        int version = ++generation;
        engine = new TextToSpeech(activity, status -> main.post(() -> {
            if (closed || version != generation || engine == null) return;
            if (status != TextToSpeech.SUCCESS) {
                stop();
                showError();
                return;
            }
            ready = true;
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) {}
                @Override public void onError(String id) {
                    main.post(() -> { if (!closed && version == generation) showError(); });
                }
            });
            speakPending();
        }));
    }

    private void speakPending() {
        if (pendingText == null) return;
        String text = pendingText;
        pendingText = null;
        int language = engine.setLanguage(Locale.forLanguageTag("sl-SI"));
        if (language < TextToSpeech.LANG_AVAILABLE) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.speech_missing_voice)
                    .setMessage(R.string.speech_install_slovenian)
                    .setPositiveButton(R.string.speech_settings, (dialog, which) -> {
                        try { activity.startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                        catch (ActivityNotFoundException error) {
                            try { activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                            catch (ActivityNotFoundException unavailable) { showError(); }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return; // Never substitute a German or English voice for Slovenian pronunciation.
        }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "menu-title") == TextToSpeech.ERROR) showError();
    }

    private void showError() {
        if (!activity.isDestroyed() && !activity.isFinishing()) {
            Toast.makeText(activity, R.string.speech_failed, Toast.LENGTH_LONG).show();
        }
    }

    void stop() {
        generation++;
        pendingText = null;
        if (engine != null) {
            if (ready) engine.stop();
            engine.shutdown();
            engine = null;
        }
        ready = false;
    }

    @Override public void close() { closed = true; stop(); }
}
