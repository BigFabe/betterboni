package app.betterboni;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RestaurantClassifier implements AutoCloseable {
    interface Listener {
        void onClassification(String id, List<RestaurantTaxonomy.Tag> labels);
        void onError(String message);
    }
    private final Listener listener;
    private final Cache cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final Set<String> attempted = new HashSet<>(), active = new HashSet<>();
    private ClassificationEndpoint config;
    private boolean enabled;
    private volatile boolean closed, blocked;
    private volatile int generation;

    RestaurantClassifier(Context context, Listener listener) {
        this.listener = listener;
        cache = new Cache(context.getApplicationContext());
    }

    boolean configure(boolean enabled, ClassificationEndpoint config) {
        if (this.config != null && this.enabled == enabled && this.config.identity().equals(config.identity())
                && this.config.apiKey.equals(config.apiKey)) return false;
        generation++;
        this.enabled = enabled;
        this.config = config;
        blocked = false;
        attempted.clear();
        active.clear();
        return true;
    }

    void retry() { blocked = false; attempted.clear(); }

    void request(Restaurant restaurant) {
        if (closed || !enabled || blocked || active.contains(restaurant.id) || !attempted.add(restaurant.id)) return;
        active.add(restaurant.id);
        int version = generation;
        ClassificationEndpoint endpoint = config;
        disk.execute(() -> {
            try {
                if (!current(version)) return;
                MenuEvidence evidence = cache.menu(restaurant.id);
                if (evidence != null) classify(restaurant, evidence, endpoint, version);
                else network.execute(() -> {
                    try {
                        if (!current(version)) return;
                        MenuEvidence fetched = MenuEvidence.fetch(restaurant);
                        if (!current(version)) return;
                        disk.execute(() -> {
                            try {
                                if (!current(version)) return;
                                cache.saveMenu(restaurant.id, fetched);
                                classify(restaurant, fetched, endpoint, version);
                            } catch (Exception error) { fail(restaurant.id, version, error); }
                        });
                    } catch (Exception error) { fail(restaurant.id, version, error); }
                });
            } catch (Exception error) { fail(restaurant.id, version, error); }
        });
    }

    void observe(Restaurant restaurant, List<String[]> menus) {
        if (closed) return;
        int version = generation;
        disk.execute(() -> {
            try {
                MenuEvidence evidence = new MenuEvidence(menus);
                cache.saveMenu(restaurant.id, evidence);
                main.post(() -> {
                    if (!current(version)) return;
                    attempted.remove(restaurant.id);
                    request(restaurant);
                });
            } catch (Exception error) { fail(restaurant.id, version, error); }
        });
    }

    private void classify(Restaurant restaurant, MenuEvidence evidence, ClassificationEndpoint endpoint, int version) throws Exception {
        String identity = endpoint.identity() + "\n" + restaurant.name;
        JSONObject saved = cache.result(restaurant.id, evidence.hash, identity);
        if (saved != null) { finish(restaurant.id, version, saved); return; }
        if (evidence.dishes.length() < 3) {
            JSONObject empty = new JSONObject();
            for (RestaurantTaxonomy.Tag tag : RestaurantTaxonomy.Tag.values()) empty.put(tag.id, 0);
            cache.saveResult(restaurant.id, evidence.hash, identity, empty);
            finish(restaurant.id, version, empty);
            return;
        }
        network.execute(() -> {
            try {
                if (!current(version) || blocked) throw new java.util.concurrent.CancellationException();
                JSONObject scores = endpoint.classify(evidence, restaurant.name);
                if (!current(version)) return;
                disk.execute(() -> {
                    try {
                        if (!current(version)) return;
                        cache.saveResult(restaurant.id, evidence.hash, identity, scores);
                        finish(restaurant.id, version, scores);
                    } catch (Exception error) { fail(restaurant.id, version, error); }
                });
            } catch (Exception error) { fail(restaurant.id, version, error); }
        });
    }

    private boolean current(int version) { return !closed && version == generation; }

    private void finish(String id, int version, JSONObject scores) throws Exception {
        List<RestaurantTaxonomy.Tag> labels = RestaurantTaxonomy.select(scores);
        main.post(() -> {
            if (!current(version)) return;
            active.remove(id);
            listener.onClassification(id, labels);
        });
    }

    private void fail(String id, int version, Exception error) {
        main.post(() -> {
            if (!current(version)) return;
            active.remove(id);
            if (error instanceof java.util.concurrent.CancellationException) return;
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (message.startsWith("HTTP 401") || message.startsWith("HTTP 402") || message.startsWith("HTTP 403")) blocked = true;
            listener.onError(message);
        });
    }

    @Override public void close() {
        closed = true;
        generation++;
        network.shutdownNow();
        disk.execute(cache::close);
        disk.shutdown();
    }

    private static final class Cache extends SQLiteOpenHelper {
        Cache(Context context) { super(context, "restaurant_classifications.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE menus (restaurant_id TEXT PRIMARY KEY, menu_json TEXT NOT NULL, fetched_day TEXT NOT NULL)");
            db.execSQL("CREATE TABLE results (restaurant_id TEXT NOT NULL, menu_hash TEXT NOT NULL, taxonomy TEXT NOT NULL, engine TEXT NOT NULL, scores TEXT NOT NULL, PRIMARY KEY (restaurant_id, menu_hash, taxonomy, engine))");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("No classification migration defined.");
        }
        private String today() { return LocalDate.now(ZoneId.of("Europe/Ljubljana")).toString(); }
        MenuEvidence menu(String id) throws Exception {
            try (Cursor row = getReadableDatabase().query("menus", new String[]{"menu_json"},
                    "restaurant_id=? AND fetched_day=?", new String[]{id, today()}, null, null, null)) {
                return row.moveToFirst() ? MenuEvidence.fromJson(row.getString(0)) : null;
            }
        }
        void saveMenu(String id, MenuEvidence menu) {
            ContentValues values = new ContentValues();
            values.put("restaurant_id", id); values.put("menu_json", menu.dishes.toString()); values.put("fetched_day", today());
            if (getWritableDatabase().insertWithOnConflict("menus", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) throw new IllegalStateException("Could not cache menu evidence.");
        }
        JSONObject result(String id, String hash, String engine) throws Exception {
            try (Cursor row = getReadableDatabase().query("results", new String[]{"scores"},
                    "restaurant_id=? AND menu_hash=? AND taxonomy=? AND engine=?",
                    new String[]{id, hash, RestaurantTaxonomy.VERSION, engine}, null, null, null)) {
                return row.moveToFirst() ? new JSONObject(row.getString(0)) : null;
            }
        }
        void saveResult(String id, String hash, String engine, JSONObject scores) {
            ContentValues values = new ContentValues();
            values.put("restaurant_id", id); values.put("menu_hash", hash); values.put("taxonomy", RestaurantTaxonomy.VERSION);
            values.put("engine", engine); values.put("scores", scores.toString());
            if (getWritableDatabase().insertWithOnConflict("results", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) throw new IllegalStateException("Could not cache classification.");
        }
    }
}
