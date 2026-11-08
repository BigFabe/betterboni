package app.betterboni;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class MenuTranslator implements AutoCloseable {
    static final class WifiRequiredException extends Exception {}
    private final Context context;
    private final android.content.res.Resources resources;
    private final String targetLanguage;
    private final TranslationCache cache;
    private Translator translator;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService llmNetwork = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    // Accessed on the main thread; repeated dishes share the same task.
    private final Map<String, TaskCompletionSource<String>> inFlight = new HashMap<>();
    private final Map<String, TaskCompletionSource<String>> llmPending = new LinkedHashMap<>();
    private final Runnable flushLlm = this::flushLlm;
    private Task<Void> modelSetup;
    private boolean modelsReady, useLlm;
    private LlmEndpoint endpoint;
    private String engineId;
    private volatile int generation;
    private volatile boolean closed;
    private Exception cacheFailure;

    MenuTranslator(Context context, String targetLanguage) {
        this.context = context.getApplicationContext();
        this.resources = context.getResources();
        this.targetLanguage = targetLanguage;
        cache = new TranslationCache(this.context);
    }

    void configure(boolean custom, LlmEndpoint config) {
        String nextId = custom ? config.cacheId() : "mlkit-17.0.3:sl-" + targetLanguage;
        boolean sameCredentials = endpoint != null && endpoint.apiKey.equals(config.apiKey);
        if (nextId.equals(engineId) && sameCredentials) return;
        generation++;
        for (TaskCompletionSource<String> result : new ArrayList<>(inFlight.values())) result.trySetException(new CancellationException());
        inFlight.clear();
        llmPending.clear();
        main.removeCallbacks(flushLlm);
        useLlm = custom;
        endpoint = config;
        engineId = nextId;
        io.execute(() -> {
            try { cache.activate(nextId); cacheFailure = null; }
            catch (Exception error) { cacheFailure = error; }
        });
    }

    private boolean current(int version) { return !closed && generation == version; }

    Task<String> translate(String original, boolean allowMobile, Consumer<String> progress) {
        String source = Normalizer.normalize(original, Normalizer.Form.NFC).replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (source.isEmpty()) return Tasks.forResult("");
        if (closed) return Tasks.forException(new CancellationException());
        TaskCompletionSource<String> existing = inFlight.get(source);
        if (existing != null) return existing.getTask();
        int version = generation;
        String activeEngine = engineId;
        TaskCompletionSource<String> result = new TaskCompletionSource<>();
        inFlight.put(source, result);
        result.getTask().addOnCompleteListener(done -> {
            if (inFlight.get(source) == result) inFlight.remove(source);
        });
        io.execute(() -> {
            try {
                if (!current(version)) throw new CancellationException();
                if (cacheFailure != null) throw cacheFailure;
                String saved = cache.get(source, activeEngine, targetLanguage);
                if (!current(version)) throw new CancellationException();
                if (saved != null) { result.trySetResult(saved); return; }
                main.post(() -> {
                    if (!current(version)) { result.trySetException(new CancellationException()); return; }
                    if (useLlm) {
                        llmPending.put(source, result);
                        main.removeCallbacks(flushLlm);
                        main.postDelayed(flushLlm, 40);
                        return;
                    }
                    if (!modelsReady) progress.accept(resources.getString(R.string.model_downloading));
                    prepareModels(allowMobile).addOnSuccessListener(unused -> {
                        if (!current(version)) { result.trySetException(new CancellationException()); return; }
                        progress.accept(resources.getString(R.string.translating));
                        translator.translate(source).addOnSuccessListener(translated -> {
                            if (!current(version)) { result.trySetException(new CancellationException()); return; }
                            save(source, translated, activeEngine, version, result);
                        }).addOnFailureListener(result::trySetException);
                    }).addOnFailureListener(result::trySetException);
                });
            } catch (Exception error) { result.trySetException(error); }
        });
        return result.getTask();
    }

    private void save(String source, String translated, String engine, int version, TaskCompletionSource<String> result) {
        if (!current(version)) { result.trySetException(new CancellationException()); return; }
        if (translated == null || translated.trim().isEmpty()) {
            result.trySetException(new IllegalStateException("Empty translation"));
            return;
        }
        io.execute(() -> {
            try {
                if (!current(version)) throw new CancellationException();
                cache.put(source, translated, engine, targetLanguage);
                if (!current(version)) throw new CancellationException();
                result.trySetResult(translated);
            } catch (Exception error) { result.trySetException(error); }
        });
    }

    private void flushLlm() {
        if (closed || llmPending.isEmpty()) return;
        int version = generation;
        LlmEndpoint config = endpoint;
        String engine = engineId;
        List<Map.Entry<String, TaskCompletionSource<String>>> pending = new ArrayList<>(llmPending.entrySet());
        llmPending.clear();
        for (int start = 0; start < pending.size(); start += 8) {
            Map<String, TaskCompletionSource<String>> batch = new LinkedHashMap<>();
            for (int i = start; i < Math.min(start + 8, pending.size()); i++) {
                batch.put(pending.get(i).getKey(), pending.get(i).getValue());
            }
            llmNetwork.execute(() -> {
                try {
                    if (!current(version)) throw new CancellationException();
                    Map<String, String> translated = config.translate(new ArrayList<>(batch.keySet()));
                    main.post(() -> {
                        for (Map.Entry<String, TaskCompletionSource<String>> entry : batch.entrySet()) {
                            if (!current(version)) entry.getValue().trySetException(new CancellationException());
                            else save(entry.getKey(), translated.get(entry.getKey()), engine, version, entry.getValue());
                        }
                    });
                } catch (Exception error) {
                    for (TaskCompletionSource<String> result : batch.values()) result.trySetException(error);
                }
            });
        }
    }

    private Task<Void> prepareModels(boolean allowMobile) {
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.SLOVENIAN)
                    .setTargetLanguage(targetLanguage).build());
        }
        if (modelSetup != null && (!modelSetup.isComplete() || modelSetup.isSuccessful())) return modelSetup;
        TaskCompletionSource<Void> setup = new TaskCompletionSource<>();
        modelSetup = setup.getTask();
        RemoteModelManager manager = RemoteModelManager.getInstance();
        TranslateRemoteModel sl = new TranslateRemoteModel.Builder(TranslateLanguage.SLOVENIAN).build();
        List<Task<Boolean>> checks = new ArrayList<>();
        checks.add(manager.isModelDownloaded(sl));
        // English is the built-in pivot; it has no downloadable translation model.
        if (!targetLanguage.equals(TranslateLanguage.ENGLISH)) {
            TranslateRemoteModel target = new TranslateRemoteModel.Builder(targetLanguage).build();
            checks.add(manager.isModelDownloaded(target));
        }
        Tasks.whenAllSuccess(checks)
                .addOnSuccessListener(downloaded -> {
                    if (closed) { setup.trySetException(new CancellationException()); return; }
                    if (downloaded.stream().allMatch(Boolean.TRUE::equals)) {
                        modelsReady = true;
                        setup.trySetResult(null);
                        return;
                    }
                    ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkCapabilities network = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
                    if (!allowMobile && (network == null || !network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) {
                        setup.trySetException(new WifiRequiredException());
                        return;
                    }
                    DownloadConditions.Builder conditions = new DownloadConditions.Builder();
                    if (!allowMobile) conditions.requireWifi();
                    translator.downloadModelIfNeeded(conditions.build())
                            .addOnSuccessListener(unused -> { modelsReady = true; setup.trySetResult(null); })
                            .addOnFailureListener(setup::trySetException);
                }).addOnFailureListener(setup::trySetException);
        return modelSetup;
    }

    @Override public void close() {
        closed = true;
        generation++;
        main.removeCallbacks(flushLlm);
        for (TaskCompletionSource<String> result : new ArrayList<>(inFlight.values())) result.trySetException(new CancellationException());
        if (translator != null) translator.close();
        llmNetwork.shutdownNow();
        io.execute(cache::close);
        io.shutdown();
    }
}
