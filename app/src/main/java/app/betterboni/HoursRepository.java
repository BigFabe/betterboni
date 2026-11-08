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
import org.jsoup.nodes.Document;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HoursRepository implements AutoCloseable {
    interface Listener {
        void onCached(Map<String, String> schedules, Map<String, Boolean> menus, Map<String, OpeningHours.Status> states);
        void onHours(String id, String schedule);
        void onMenu(String id, Boolean hasMenu, MenuEvidence evidence);
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
        ordered.removeIf(restaurant -> !requested.add(restaurant.id));
        if (ordered.isEmpty()) return;
        remaining += ordered.size();
        listener.onLoading(true);
        disk.execute(() -> {
            if (closed) return;
            Map<String, String> schedules = new LinkedHashMap<>();
            Map<String, Boolean> menus = new LinkedHashMap<>();
            Map<String, OpeningHours.Status> states = new LinkedHashMap<>();
            Set<String> ids = new HashSet<>();
            for (Restaurant restaurant : ordered) ids.add(restaurant.id);
            long now = System.currentTimeMillis();
            String day = today();
            try (Cursor rows = cache.getReadableDatabase().rawQuery(
                    "SELECT h.restaurant_id, h.schedule, h.fetched_at, m.has_menu, m.fetched_day "
                            + "FROM hours h LEFT JOIN menu_availability m ON h.restaurant_id=m.restaurant_id", null)) {
                while (rows.moveToNext()) {
                    String id = rows.getString(0);
                    if (!ids.contains(id)) continue;
                    long age = now - rows.getLong(2);
                    if (age >= 0 && age < MAX_AGE_MS) {
                        String schedule = rows.getString(1);
                        schedules.put(id, schedule);
                        states.put(id, OpeningHours.status(schedule));
                    }
                    if (!rows.isNull(3) && day.equals(rows.getString(4))) menus.put(id, rows.getInt(3) != 0);
                }
            } catch (Exception error) {
                android.util.Log.w("BetterBoni", "Restaurant-details cache unavailable", error);
            }
            List<Restaurant> missing = new ArrayList<>();
            for (Restaurant restaurant : ordered) {
                if (!schedules.containsKey(restaurant.id) || !menus.containsKey(restaurant.id)) missing.add(restaurant);
            }
            int cachedCount = ordered.size() - missing.size();
            main.post(() -> {
                if (closed) return;
                remaining -= cachedCount;
                listener.onCached(schedules, menus, states);
                listener.onLoading(remaining > 0);
            });
            for (Restaurant restaurant : missing) {
                if (closed) return;
                try { downloads.execute(() -> download(restaurant)); }
                catch (java.util.concurrent.RejectedExecutionException ignored) { /* Activity was closed. */ }
            }
        });
    }

    private void download(Restaurant restaurant) {
        try {
            if (closed) return;
            Document document = Jsoup.connect(restaurant.url)
                    .userAgent("BetterBoni/0.1 (Android; unofficial student meal directory)")
                    .timeout(20000).maxBodySize(8 * 1024 * 1024).get();
            String schedule = OpeningHours.extract(document);
            MenuEvidence fetched = null;
            try { fetched = MenuEvidence.fetch(restaurant, document); }
            catch (Exception error) { android.util.Log.w("BetterBoni", "Menu availability could not be checked", error); }
            MenuEvidence evidence = fetched;
            Boolean hasMenu = evidence == null ? null : evidence.dishes.length() > 0;
            if (closed) return;
            disk.execute(() -> {
                try {
                    save(restaurant.id, schedule);
                    if (hasMenu != null) saveMenu(restaurant.id, hasMenu);
                } catch (Exception error) { android.util.Log.w("BetterBoni", "Restaurant details could not be cached", error); }
                finish(restaurant.id, schedule, hasMenu, evidence);
            });
        } catch (Exception error) {
            if (!closed) finish(restaurant.id, null, null, null);
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

    void rememberMenu(String id, boolean hasMenu) {
        if (closed) return;
        disk.execute(() -> {
            try { saveMenu(id, hasMenu); }
            catch (Exception error) { android.util.Log.w("BetterBoni", "Menu availability could not be cached", error); }
            main.post(() -> { if (!closed) listener.onMenu(id, hasMenu, null); });
        });
    }

    private String today() { return LocalDate.now(ZoneId.of("Europe/Ljubljana")).toString(); }

    private void saveMenu(String id, boolean hasMenu) {
        ContentValues values = new ContentValues();
        values.put("restaurant_id", id);
        values.put("has_menu", hasMenu ? 1 : 0);
        values.put("fetched_day", today());
        if (cache.getWritableDatabase().insertWithOnConflict("menu_availability", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("Menu availability could not be cached.");
        }
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

    private void finish(String id, String schedule, Boolean hasMenu, MenuEvidence evidence) {
        main.post(() -> {
            if (closed) return;
            if (schedule == null || hasMenu == null) failed.add(id);
            listener.onHours(id, schedule);
            listener.onMenu(id, hasMenu, evidence);
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
        Cache(Context context) { super(context, "opening_hours.db", null, 2); }
        private void createMenuTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE menu_availability (restaurant_id TEXT PRIMARY KEY, has_menu INTEGER NOT NULL, fetched_day TEXT NOT NULL)");
        }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE hours (restaurant_id TEXT PRIMARY KEY, schedule TEXT NOT NULL, fetched_at INTEGER NOT NULL)");
            createMenuTable(db);
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) createMenuTable(db);
        }
    }
}
