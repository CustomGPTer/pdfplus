package uk.jj.pdfplus.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PdfFile(
    val path: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val pages: Int = 0,
    val favourite: Boolean = false,
    val lastOpened: Long = 0,
    val lastPage: Int = 0,
    val tags: String = "",
    val source: String = "phone",   // phone | private | drive
    val driveId: String = "",
    val indexed: Boolean = false,
    val colourMode: String = "",
    val viewMode: Int = -1,
    val zoom: Float = 1f,
) {
    val tagList: List<String> get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val folder: String get() = path.substringBeforeLast('/', "")
}

data class Bookmark(val id: Long, val path: String, val page: Int, val label: String, val created: Long)
data class Collection(val id: Long, val name: String, val count: Int)
data class TextHit(val path: String, val name: String, val page: Int, val snippet: String)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "library.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE files(
                path TEXT PRIMARY KEY, name TEXT NOT NULL, size INTEGER, mtime INTEGER, pages INTEGER DEFAULT 0,
                fav INTEGER DEFAULT 0, lastOpened INTEGER DEFAULT 0, lastPage INTEGER DEFAULT 0, tags TEXT DEFAULT '',
                source TEXT DEFAULT 'phone', driveId TEXT DEFAULT '', indexed INTEGER DEFAULT 0,
                colourMode TEXT DEFAULT '', viewMode INTEGER DEFAULT -1, zoom REAL DEFAULT 1)"""
        )
        db.execSQL("CREATE INDEX files_opened ON files(lastOpened)")
        db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT, page INTEGER, label TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE collections(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE)")
        db.execSQL("CREATE TABLE collection_files(cid INTEGER, path TEXT, PRIMARY KEY(cid, path))")
        db.execSQL("CREATE VIRTUAL TABLE pagetext USING fts4(path, page, body, notindexed=path, notindexed=page)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun Cursor.toFile() = PdfFile(
        path = getString(0), name = getString(1), size = getLong(2), mtime = getLong(3), pages = getInt(4),
        favourite = getInt(5) != 0, lastOpened = getLong(6), lastPage = getInt(7), tags = getString(8) ?: "",
        source = getString(9) ?: "phone", driveId = getString(10) ?: "", indexed = getInt(11) != 0,
        colourMode = getString(12) ?: "", viewMode = getInt(13), zoom = getFloat(14),
    )

    private val cols = "path,name,size,mtime,pages,fav,lastOpened,lastPage,tags,source,driveId,indexed,colourMode,viewMode,zoom"

    private fun query(where: String, args: Array<String> = emptyArray(), order: String = "name COLLATE NOCASE", limit: Int = 0): List<PdfFile> {
        val out = ArrayList<PdfFile>()
        val sql = "SELECT $cols FROM files WHERE $where ORDER BY $order" + if (limit > 0) " LIMIT $limit" else ""
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(c.toFile()) }
        return out
    }

    fun allFiles(source: String = "phone"): List<PdfFile> = query("source=?", arrayOf(source))
    fun everyFile(): List<PdfFile> = query("1")
    fun recent(limit: Int = 60): List<PdfFile> = query("lastOpened>0 AND source!='private'", order = "lastOpened DESC", limit = limit)
    fun favourites(): List<PdfFile> = query("fav=1 AND source!='private'")
    fun file(path: String): PdfFile? = query("path=?", arrayOf(path)).firstOrNull()
    fun byName(q: String): List<PdfFile> = query("name LIKE ? AND source!='private'", arrayOf("%$q%"), limit = 200)
    fun withTag(tag: String): List<PdfFile> = query("(','||tags||',') LIKE ? AND source!='private'", arrayOf("%,$tag,%"))
    fun unindexed(limit: Int): List<PdfFile> = query("indexed=0 AND source='phone'", order = "lastOpened DESC, mtime DESC", limit = limit)
    fun withoutPageCount(limit: Int): List<PdfFile> = query("pages=0", order = "lastOpened DESC, mtime DESC", limit = limit)

    fun allTags(): List<String> {
        val set = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        readableDatabase.rawQuery("SELECT tags FROM files WHERE tags!=''", null).use { c ->
            while (c.moveToNext()) c.getString(0).split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { set.add(it) }
        }
        return set.toList()
    }

    /** Insert new files and refresh size/mtime of changed ones; keeps user data (favourite, tags, position). */
    fun upsertScanned(found: List<PdfFile>, source: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = HashMap<String, Long>()
            db.rawQuery("SELECT path, mtime FROM files WHERE source=?", arrayOf(source)).use { c ->
                while (c.moveToNext()) existing[c.getString(0)] = c.getLong(1)
            }
            for (f in found) {
                val old = existing.remove(f.path)
                if (old == null) {
                    db.insertWithOnConflict("files", null, ContentValues().apply {
                        put("path", f.path); put("name", f.name); put("size", f.size); put("mtime", f.mtime)
                        put("source", source); put("driveId", f.driveId)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                } else if (old != f.mtime) {
                    db.update("files", ContentValues().apply {
                        put("size", f.size); put("mtime", f.mtime); put("pages", 0); put("indexed", 0)
                    }, "path=?", arrayOf(f.path))
                    db.delete("pagetext", "path=?", arrayOf(f.path))
                }
            }
            for (gone in existing.keys) removeInternal(db, gone)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun removeInternal(db: SQLiteDatabase, path: String) {
        db.delete("files", "path=?", arrayOf(path))
        db.delete("pagetext", "path=?", arrayOf(path))
        db.delete("bookmarks", "path=?", arrayOf(path))
        db.delete("collection_files", "path=?", arrayOf(path))
    }

    fun remove(path: String) = removeInternal(writableDatabase, path)

    fun ensure(path: String, name: String, size: Long, mtime: Long, source: String = "phone") {
        writableDatabase.insertWithOnConflict("files", null, ContentValues().apply {
            put("path", path); put("name", name); put("size", size); put("mtime", mtime); put("source", source)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun rename(oldPath: String, newPath: String, newName: String) {
        val db = writableDatabase
        db.execSQL("UPDATE files SET path=?, name=? WHERE path=?", arrayOf(newPath, newName, oldPath))
        db.execSQL("UPDATE bookmarks SET path=? WHERE path=?", arrayOf(newPath, oldPath))
        db.execSQL("UPDATE collection_files SET path=? WHERE path=?", arrayOf(newPath, oldPath))
        db.execSQL("UPDATE pagetext SET path=? WHERE path=?", arrayOf(newPath, oldPath))
    }

    private fun update(path: String, block: ContentValues.() -> Unit) {
        writableDatabase.update("files", ContentValues().apply(block), "path=?", arrayOf(path))
    }

    fun setSource(path: String, source: String) = update(path) { put("source", source) }
    fun setFavourite(path: String, fav: Boolean) = update(path) { put("fav", if (fav) 1 else 0) }
    fun setTags(path: String, tags: List<String>) = update(path) { put("tags", tags.joinToString(",")) }
    fun setPages(path: String, pages: Int) = update(path) { put("pages", pages) }
    fun setIndexed(path: String) = update(path) { put("indexed", 1) }
    fun markOpened(path: String) = update(path) { put("lastOpened", System.currentTimeMillis()) }
    fun setOpened(path: String, time: Long) = update(path) { put("lastOpened", time) }
    fun clearRecent(path: String) = update(path) { put("lastOpened", 0) }
    fun savePosition(path: String, page: Int, zoom: Float) = update(path) { put("lastPage", page); put("zoom", zoom) }
    fun saveViewPrefs(path: String, colourMode: String, viewMode: Int) = update(path) { put("colourMode", colourMode); put("viewMode", viewMode) }

    // Bookmarks
    fun bookmarks(path: String): List<Bookmark> {
        val out = ArrayList<Bookmark>()
        readableDatabase.rawQuery("SELECT id,path,page,label,created FROM bookmarks WHERE path=? ORDER BY page", arrayOf(path)).use { c ->
            while (c.moveToNext()) out.add(Bookmark(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3) ?: "", c.getLong(4)))
        }
        return out
    }

    fun allBookmarks(): List<Bookmark> {
        val out = ArrayList<Bookmark>()
        readableDatabase.rawQuery("SELECT id,path,page,label,created FROM bookmarks ORDER BY path, page", null).use { c ->
            while (c.moveToNext()) out.add(Bookmark(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3) ?: "", c.getLong(4)))
        }
        return out
    }

    fun addBookmark(path: String, page: Int, label: String, created: Long = System.currentTimeMillis()) {
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("path", path); put("page", page); put("label", label); put("created", created)
        })
    }

    fun deleteBookmark(id: Long) { writableDatabase.delete("bookmarks", "id=?", arrayOf(id.toString())) }
    fun deleteBookmarkAt(path: String, page: Int) { writableDatabase.delete("bookmarks", "path=? AND page=?", arrayOf(path, page.toString())) }
    fun clearBookmarks(path: String) { writableDatabase.delete("bookmarks", "path=?", arrayOf(path)) }

    // Collections
    fun collections(): List<Collection> {
        val out = ArrayList<Collection>()
        readableDatabase.rawQuery(
            "SELECT c.id, c.name, (SELECT COUNT(*) FROM collection_files f WHERE f.cid=c.id) FROM collections c ORDER BY c.name COLLATE NOCASE", null
        ).use { c -> while (c.moveToNext()) out.add(Collection(c.getLong(0), c.getString(1), c.getInt(2))) }
        return out
    }

    fun addCollection(name: String): Long =
        writableDatabase.insertWithOnConflict("collections", null, ContentValues().apply { put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)

    fun deleteCollection(id: Long) {
        writableDatabase.delete("collections", "id=?", arrayOf(id.toString()))
        writableDatabase.delete("collection_files", "cid=?", arrayOf(id.toString()))
    }

    fun collectionFiles(id: Long): List<PdfFile> =
        query("path IN (SELECT path FROM collection_files WHERE cid=?)", arrayOf(id.toString()))

    fun collectionsOf(path: String): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT cid FROM collection_files WHERE path=?", arrayOf(path)).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun setInCollection(id: Long, path: String, member: Boolean) {
        if (member) writableDatabase.insertWithOnConflict("collection_files", null, ContentValues().apply { put("cid", id); put("path", path) }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("collection_files", "cid=? AND path=?", arrayOf(id.toString(), path))
    }

    // Full-text index
    fun indexPages(path: String, pages: List<Pair<Int, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("pagetext", "path=?", arrayOf(path))
            val st = db.compileStatement("INSERT INTO pagetext(path,page,body) VALUES(?,?,?)")
            for ((page, body) in pages) {
                if (body.isBlank()) continue
                st.bindString(1, path); st.bindLong(2, page.toLong()); st.bindString(3, body); st.executeInsert(); st.clearBindings()
            }
            db.update("files", ContentValues().apply { put("indexed", 1) }, "path=?", arrayOf(path))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun searchText(query: String, limit: Int = 150): List<TextHit> {
        val terms = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" ") { "$it*" }
        val out = ArrayList<TextHit>()
        try {
            readableDatabase.rawQuery(
                "SELECT t.path, t.page, snippet(pagetext, '', '', '…', -1, 14), f.name FROM pagetext t JOIN files f ON f.path=t.path " +
                    "WHERE pagetext MATCH ? AND f.source!='private' LIMIT $limit", arrayOf(match)
            ).use { c ->
                while (c.moveToNext()) out.add(TextHit(c.getString(0), c.getString(3), c.getInt(1), c.getString(2).replace('\n', ' ')))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun indexedCount(): Pair<Int, Int> {
        readableDatabase.rawQuery("SELECT SUM(indexed), COUNT(*) FROM files WHERE source='phone'", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0
        }
    }
}
