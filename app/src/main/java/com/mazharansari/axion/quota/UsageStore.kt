package com.mazharansari.axion.quota

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** usage.db (spec §4.2): one row per LLM call / agy run, plus quota snapshots and learned limits. Pruned after 35 days. */
class UsageStore(ctx: Context) : SQLiteOpenHelper(ctx, "usage.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usage_event(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, source TEXT, ref TEXT, provider TEXT, model TEXT, ok INTEGER, code INTEGER, in_tok INTEGER, out_tok INTEGER)")
        db.execSQL("CREATE INDEX ue_ref ON usage_event(ref, at)")
        db.execSQL("CREATE TABLE snapshot(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, source TEXT, ref TEXT, model TEXT, win TEXT, unit TEXT, lim REAL, rem REAL, reset INTEGER, tag TEXT, label TEXT, raw TEXT)")
        db.execSQL("CREATE INDEX sn_ref ON snapshot(ref, win, at)")
        db.execSQL("CREATE TABLE limit_override(ref TEXT, model TEXT, win TEXT, lim REAL, set_by TEXT, at INTEGER, PRIMARY KEY(ref, model, win))")
        db.execSQL("CREATE TABLE cooldown(k TEXT PRIMARY KEY, until INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun event(at: Long, source: String, ref: String, provider: String, model: String, ok: Boolean, code: Int, inTok: Long = 0, outTok: Long = 0) {
        writableDatabase.insert("usage_event", null, ContentValues().apply {
            put("at", at); put("source", source); put("ref", ref); put("provider", provider); put("model", model); put("ok", if (ok) 1 else 0); put("code", code); put("in_tok", inTok); put("out_tok", outTok)
        })
    }

    fun snapshot(at: Long, source: String, ref: String, model: String?, r: Reading) {
        writableDatabase.insert("snapshot", null, ContentValues().apply {
            put("at", at); put("source", source); put("ref", ref); put("model", model ?: r.model ?: ""); put("win", r.window); put("unit", r.unit)
            r.limit?.let { put("lim", it) }; r.remaining?.let { put("rem", it) }; r.resetAt?.let { put("reset", it) }
            put("tag", r.tag.name); put("label", r.label); put("raw", r.raw.take(4000))
        })
    }

    fun override(ref: String, model: String, win: String, lim: Double, by: String) {
        writableDatabase.insertWithOnConflict("limit_override", null, ContentValues().apply { put("ref", ref); put("model", model); put("win", win); put("lim", lim); put("set_by", by); put("at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun overrideOf(ref: String, model: String, win: String = "day"): Pair<Double, String>? =
        readableDatabase.rawQuery("SELECT lim, set_by FROM limit_override WHERE ref=? AND model=? AND win=?", arrayOf(ref, model, win)).use { c -> if (c.moveToFirst()) c.getDouble(0) to c.getString(1) else null }

    /** Latest snapshot for ref (+model) in a window. Returns (reading, at). */
    fun latest(ref: String, win: String, model: String? = null): Pair<Reading, Long>? {
        val q = if (model == null) "SELECT at,model,win,unit,lim,rem,reset,tag,label,raw FROM snapshot WHERE ref=? AND win=? ORDER BY at DESC LIMIT 1"
            else "SELECT at,model,win,unit,lim,rem,reset,tag,label,raw FROM snapshot WHERE ref=? AND win=? AND model=? ORDER BY at DESC LIMIT 1"
        val args = if (model == null) arrayOf(ref, win) else arrayOf(ref, win, model)
        return readableDatabase.rawQuery(q, args).use { c -> if (c.moveToFirst()) readingOf(c) to c.getLong(0) else null }
    }

    /** Every snapshot for ref taken in the newest probe/response (same timestamp). */
    fun latestSet(ref: String): Pair<List<Reading>, Long>? {
        val at = readableDatabase.rawQuery("SELECT MAX(at) FROM snapshot WHERE ref=?", arrayOf(ref)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null } ?: return null
        val list = readableDatabase.rawQuery("SELECT at,model,win,unit,lim,rem,reset,tag,label,raw FROM snapshot WHERE ref=? AND at=?", arrayOf(ref, at.toString())).use { c -> buildList { while (c.moveToNext()) add(readingOf(c)) } }
        return list to at
    }

    /** Remaining-fraction history for an Antigravity account since [since] (oldest first) for the per-run burn estimate. */
    fun fractions(ref: String, since: Long): List<Pair<Long, Double>> =
        readableDatabase.rawQuery("SELECT at, MIN(rem) FROM snapshot WHERE ref=? AND unit='fraction' AND at>=? AND rem IS NOT NULL GROUP BY at ORDER BY at", arrayOf(ref, since.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getDouble(1)) } }

    private fun readingOf(c: android.database.Cursor) = Reading(
        window = c.getString(2), unit = c.getString(3), limit = if (c.isNull(4)) null else c.getDouble(4), remaining = if (c.isNull(5)) null else c.getDouble(5),
        resetAt = if (c.isNull(6)) null else c.getLong(6), tag = runCatching { Tag.valueOf(c.getString(7)) }.getOrDefault(Tag.LIVE), label = c.getString(8) ?: "", model = c.getString(1), raw = c.getString(9) ?: "",
    )

    fun count(ref: String, since: Long, model: String? = null): Int {
        val (q, a) = if (model == null) "SELECT COUNT(*) FROM usage_event WHERE ref=? AND at>=?" to arrayOf(ref, since.toString())
            else "SELECT COUNT(*) FROM usage_event WHERE ref=? AND at>=? AND model=?" to arrayOf(ref, since.toString(), model)
        return readableDatabase.rawQuery(q, a).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** P1-10 (ME-4): fill in the real token counts for the latest call on [ref] (+[model]) once the provider's
     * `usage`/`usageMetadata` arrives at the end of the stream. The event row already exists (recorded when the
     * response started), so this UPDATEs it instead of adding a second row — request counts never double. */
    fun recordUsage(ref: String, model: String, usage: TokenUsage) {
        val db = writableDatabase
        val id = db.rawQuery("SELECT id FROM usage_event WHERE ref=? AND model=? ORDER BY at DESC LIMIT 1", arrayOf(ref, model)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            ?: db.rawQuery("SELECT id FROM usage_event WHERE ref=? ORDER BY at DESC LIMIT 1", arrayOf(ref)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            ?: return
        db.execSQL("UPDATE usage_event SET in_tok=?, out_tok=? WHERE id=?", arrayOf(usage.prompt, usage.completion, id))
    }

    fun tokens(ref: String, since: Long, model: String? = null): Long {
        val (q, a) = if (model == null) "SELECT COALESCE(SUM(in_tok+out_tok),0) FROM usage_event WHERE ref=? AND at>=?" to arrayOf(ref, since.toString())
            else "SELECT COALESCE(SUM(in_tok+out_tok),0) FROM usage_event WHERE ref=? AND at>=? AND model=?" to arrayOf(ref, since.toString(), model)
        return readableDatabase.rawQuery(q, a).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
    }

    /** True when any call on [ref] since [since] actually reported token counts (P1-10: 0 with no report is UNKNOWN, not "0 tokens"). */
    fun hasTokens(ref: String, since: Long, model: String? = null): Boolean {
        val (q, a) = if (model == null) "SELECT COUNT(*) FROM usage_event WHERE ref=? AND at>=? AND (in_tok>0 OR out_tok>0)" to arrayOf(ref, since.toString())
            else "SELECT COUNT(*) FROM usage_event WHERE ref=? AND at>=? AND model=? AND (in_tok>0 OR out_tok>0)" to arrayOf(ref, since.toString(), model)
        return readableDatabase.rawQuery(q, a).use { c -> if (c.moveToFirst()) c.getInt(0) > 0 else false }
    }
    fun times(source: String, since: Long, ref: String? = null): List<Long> {
        val (q, a) = if (ref == null) "SELECT at FROM usage_event WHERE source=? AND at>=? ORDER BY at" to arrayOf(source, since.toString())
            else "SELECT at FROM usage_event WHERE source=? AND at>=? AND ref=? ORDER BY at" to arrayOf(source, since.toString(), ref)
        return readableDatabase.rawQuery(q, a).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }
    fun lastCallAt(ref: String): Long = readableDatabase.rawQuery("SELECT MAX(at) FROM usage_event WHERE ref=?", arrayOf(ref)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    fun putCooldown(k: String, until: Long) { writableDatabase.insertWithOnConflict("cooldown", null, ContentValues().apply { put("k", k); put("until", until) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun cooldowns(now: Long): Map<String, Long> = readableDatabase.rawQuery("SELECT k, until FROM cooldown WHERE until>?", arrayOf(now.toString())).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }

    fun prune(now: Long) {
        val cut = (now - 35L * 86_400_000L).toString()
        writableDatabase.delete("usage_event", "at<?", arrayOf(cut)); writableDatabase.delete("snapshot", "at<?", arrayOf(cut)); writableDatabase.delete("cooldown", "until<?", arrayOf(now.toString()))
    }
}
