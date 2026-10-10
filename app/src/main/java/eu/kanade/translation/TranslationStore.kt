package eu.kanade.translation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import eu.kanade.translation.model.PageTranslation
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Keeps what was read and translated from each page, in one SQLite database in the app's private
 * storage (not in the cache, so the system does not wipe it). A page that is in the store is never
 * sent to OCR or to a translator again.
 *
 * One database means a lookup is a single indexed query, with no folder of thousands of tiny files.
 * Two kinds are kept apart, so changing the translation engine or language redoes only the translation
 * and not the OCR.
 */
object TranslationStore {

    enum class Kind(val id: Int) {
        /** Text blocks as read by OCR, not translated yet. */
        OCR(0),

        /** Blocks with their translation. */
        TRANSLATED(1),
    }

    private const val DATABASE = "translations.db"
    private const val TABLE = "pages"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var helper: Helper? = null

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (name TEXT NOT NULL, kind INTEGER NOT NULL, data TEXT NOT NULL, " +
                    "updated INTEGER NOT NULL, PRIMARY KEY (name, kind))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun database(context: Context): SQLiteDatabase {
        val current = helper ?: synchronized(this) {
            helper ?: Helper(context.applicationContext).also { helper = it }
        }
        return current.writableDatabase
    }

    /** A short stable name for a set of settings, used inside names. */
    fun key(vararg parts: String): String =
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    fun load(context: Context, kind: Kind, name: String): PageTranslation? {
        val db = database(context)
        val data = db.rawQuery(
            "SELECT data FROM $TABLE WHERE name = ? AND kind = ?",
            arrayOf(name, kind.id.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null
        return try {
            json.decodeFromString(PageTranslation.serializer(), data)
        } catch (e: Exception) {
            // A damaged row is removed so the page is processed again.
            db.delete(TABLE, "name = ? AND kind = ?", arrayOf(name, kind.id.toString()))
            null
        }
    }

    fun save(context: Context, kind: Kind, name: String, page: PageTranslation) {
        val values = ContentValues().apply {
            put("name", name)
            put("kind", kind.id)
            put("data", json.encodeToString(PageTranslation.serializer(), page))
            put("updated", System.currentTimeMillis())
        }
        database(context).insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Deletes everything of one kind, or of both kinds when [kind] is null. */
    fun clear(context: Context, kind: Kind? = null) {
        val db = database(context)
        if (kind == null) db.delete(TABLE, null, null) else db.delete(TABLE, "kind = ?", arrayOf(kind.id.toString()))
    }

    fun sizeBytes(context: Context): Long = context.getDatabasePath(DATABASE).length()
}
