package com.example.trex_kotlin

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import com.example.trex_kotlin.security.EncryptedMuscleDatabase
import com.example.trex_kotlin.trainingload.*
import org.json.JSONObject

/** AES-256 SQLCipher DB. 기존 SQLite는 검증 후 이관하고 세트 원본과 파생 부하를 한 트랜잭션으로 교체한다. */
internal class MuscleLoadStore(context: Context) : SQLiteOpenHelper(context, EncryptedMuscleDatabase.NAME,
    EncryptedMuscleDatabase.prepare(context), null, 1, 0, EncryptedMuscleDatabase.preserveOnError, null, true), MuscleLoadDatabase {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE load_sets (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, exercise_id TEXT NOT NULL, ended_at INTEGER NOT NULL, posture_set_id TEXT, model_version INTEGER NOT NULL, raw TEXT NOT NULL)")
        db.execSQL("CREATE INDEX load_sets_time ON load_sets(ended_at)")
        db.execSQL("CREATE INDEX load_sets_posture ON load_sets(posture_set_id)")
        db.execSQL("CREATE TABLE muscle_doses (set_id TEXT NOT NULL REFERENCES load_sets(id) ON DELETE CASCADE, muscle_id TEXT NOT NULL, side TEXT NOT NULL CHECK(side IN ('L','R')), dose REAL NOT NULL CHECK(dose >= 0), PRIMARY KEY(set_id,muscle_id,side))")
        db.execSQL("CREATE TABLE load_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 원본을 지우지 않는다. 다음 버전에서는 원본을 보존하는 명시적 이관을 추가한다.
        error("지원하지 않는 근육 기록 DB 버전: $oldVersion → $newVersion")
    }
    override fun sync(current: List<LoadSet>, legacy: List<LoadSet>, now: Long, corrections: Map<String, Double?>): LoadLedger {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val imported = db.rawQuery("SELECT value FROM load_meta WHERE key='legacy_v1'", null).use { it.moveToFirst() }
            if (!imported) {
                legacy.forEach { put(db, it) }
                db.execSQL("INSERT INTO load_meta(key,value) VALUES('legacy_v1','done')")
            }
            current.forEach { put(db, it) }
            corrections.forEach { (id, actual) ->
                val corrected = db.rawQuery("SELECT raw FROM load_sets WHERE posture_set_id=?", arrayOf(id)).use { cursor ->
                    buildList { while(cursor.moveToNext()) add(LoadSetJson.decode(JSONObject(cursor.getString(0))).copy(actualReps=actual)) }
                }
                corrected.forEach { put(db,it) }
            }
            db.delete("load_sets", "ended_at < ?", arrayOf(workoutRetentionStart(now).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val sets = db.rawQuery("SELECT raw FROM load_sets ORDER BY ended_at", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(LoadSetJson.decode(JSONObject(cursor.getString(0)))) }
        }
        val doses = db.rawQuery("SELECT set_id,muscle_id,side,dose FROM muscle_doses", null).use { cursor ->
            buildList { while(cursor.moveToNext()) add(cursor.getString(0) to MuscleDose(Muscle.valueOf(cursor.getString(1)),cursor.getString(2),cursor.getDouble(3))) }
        }.groupBy({it.first},{it.second})
        return LoadLedger(sets,doses)
    }
    private fun put(db: SQLiteDatabase, set: LoadSet) {
        val raw = LoadSetJson.encode(set).toString()
        val same = db.rawQuery("SELECT raw FROM load_sets WHERE id=?", arrayOf(set.id)).use { it.moveToFirst() && it.getString(0) == raw }
        if (same) return
        val row = ContentValues().apply {
            put("id", set.id); put("session_id", set.sessionId); put("exercise_id", set.exerciseId)
            put("ended_at", set.endedAt); put("posture_set_id", set.postureSetId)
            put("model_version", set.modelVersion); put("raw", raw)
        }
        db.insertWithOnConflict("load_sets", null, row, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        MuscleLoadEngine.doses(set).forEach { d ->
            db.insertOrThrow("muscle_doses", null, ContentValues().apply {
                put("set_id", set.id); put("muscle_id", d.muscle.name); put("side", d.side); put("dose", d.dose)
            })
        }
    }
}
