package app.betterboni;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.concurrent.CancellationException;

final class TranslationCache extends SQLiteOpenHelper {
    TranslationCache(Context context) {
        super(context, "translations.db", null, 2);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE translations (source TEXT NOT NULL, source_language TEXT NOT NULL, "
                + "target_language TEXT NOT NULL, engine TEXT NOT NULL, translation TEXT NOT NULL, "
                + "PRIMARY KEY (source, source_language, target_language, engine))");
        createMetadata(db);
    }

    private void createMetadata(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cache_metadata (id INTEGER PRIMARY KEY, engine TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createMetadata(db);
    }

    private String activeEngine(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT engine FROM cache_metadata WHERE id=1", null)) {
            // Existing version-1 entries all came from ML Kit.
            return cursor.moveToFirst() ? cursor.getString(0) : "mlkit-17.0.3";
        }
    }

    void activate(String engine) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!engine.equals(activeEngine(db))) db.delete("translations", null, null);
            ContentValues values = new ContentValues();
            values.put("id", 1);
            values.put("engine", engine);
            if (db.insertWithOnConflict("cache_metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
                throw new IllegalStateException("Translation engine could not be saved.");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    String get(String source, String engine, String targetLanguage) {
        try (Cursor cursor = getReadableDatabase().query("translations", new String[]{"translation"},
                "source=? AND source_language=? AND target_language=? AND engine=?",
                new String[]{source, "sl", targetLanguage, engine}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    void put(String source, String translation, String engine, String targetLanguage) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // A late response from the old engine must not restore its deleted results.
            if (!engine.equals(activeEngine(db))) throw new CancellationException();
            ContentValues values = new ContentValues();
            values.put("source", source);
            values.put("source_language", "sl");
            values.put("target_language", targetLanguage);
            values.put("engine", engine);
            values.put("translation", translation);
            if (db.insertWithOnConflict("translations", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
                throw new IllegalStateException("Translation could not be cached.");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
