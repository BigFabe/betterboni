package app.betterboni;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HoursRepository implements AutoCloseable {
    interface Listener {
        void onHours(String id, String schedule);
        void onLoading(boolean loading);
    }

    private static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private final Cache cache;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    // Limit load on the public website; nearby restaurants are queued first.
    private final ExecutorService downloads = Executors.newFixedThreadPool(2);
    private final Set<String> requested = new HashSet<>(), failed = new HashSet<>();
    private int remaining;
    private volatile boolean closed;

    HoursRepository(Context context, Listener listener) {
        cache = new Cache(context.getApplicationContext());
        this.listener = listener;
    }

    void retryFailures() {
        requested.removeAll(failed);
        failed.clear();
    }

    void load(List<Restaurant> restaurants, Location origin) {
        List<Restaurant> ordered = new ArrayList<>(restaurants);
        ordered.sort(Comparator.comparingDouble(restaurant -> restaurant.distanceFrom(origin)));
        for (Restaurant restaurant : ordered) {
            if (!requested.add(restaurant.id)) continue;
            remaining++;
            disk.execute(() -> {
                try (Cursor row = cache.getReadableDatabase().query("hours", new String[]{"schedule", "fetched_at"},
                        "restaurant_id=?", new String[]{restaurant.id}, null, null, null)) {
                    if (closed) return;
                    if (row.moveToFirst()) {
                        long age = System.currentTimeMillis() - row.getLong(1);
                        if (age >= 0 && age < MAX_AGE_MS) {
                            finish(restaurant.id, row.getString(0));
                            return;
                        }
                    }
                } catch (Exception error) {
                    android.util.Log.w("BetterBoni", "Opening-hours cache unavailable", error);
                }
                if (!closed) {
                    try { downloads.execute(() -> download(restaurant)); }
                    catch (java.util.concurrent.RejectedExecutionException ignored) { /* Activity was closed. */ }
                }
            });
        }
        listener.onLoading(remaining > 0);
    }

    private void download(Restaurant restaurant) {
        try {
            if (closed) return;
            String schedule = OpeningHours.extract(Jsoup.connect(restaurant.url)
                    .userAgent("BetterBoni/0.1 (Android; unofficial student meal directory)")
                    .timeout(20000).maxBodySize(8 * 1024 * 1024).get());
            if (closed) return;
            disk.execute(() -> {
                try { save(restaurant.id, schedule); }
                catch (Exception error) { android.util.Log.w("BetterBoni", "Opening hours could not be cached", error); }
                finish(restaurant.id, schedule);
            });
        } catch (Exception error) {
            if (!closed) finish(restaurant.id, null);
        }
    }

    // A detail-page visit also refreshes this restaurant's cached schedule.
    void remember(String id, String schedule) {
        if (closed) return;
        disk.execute(() -> {
            try { save(id, schedule); }
            catch (Exception error) { android.util.Log.w("BetterBoni", "Opening hours could not be cached", error); }
            main.post(() -> { if (!closed) listener.onHours(id, schedule); });
        });
    }

    private void save(String id, String schedule) {
        ContentValues values = new ContentValues();
        values.put("restaurant_id", id);
        values.put("schedule", schedule);
        values.put("fetched_at", System.currentTimeMillis());
        if (cache.getWritableDatabase().insertWithOnConflict("hours", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("Opening hours could not be cached.");
        }
    }

    private void finish(String id, String schedule) {
        main.post(() -> {
            if (closed) return;
            if (schedule == null) failed.add(id);
            listener.onHours(id, schedule);
            remaining--;
            listener.onLoading(remaining > 0);
        });
    }

    @Override public void close() {
        closed = true;
        downloads.shutdownNow();
        disk.execute(cache::close);
        disk.shutdown();
    }

    private static final class Cache extends SQLiteOpenHelper {
        Cache(Context context) { super(context, "opening_hours.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE hours (restaurant_id TEXT PRIMARY KEY, schedule TEXT NOT NULL, fetched_at INTEGER NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("No opening-hours migration defined.");
        }
    }
}
