package com.medmission.survey.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A tablet in the field can be carrying unsent surveys and its saved laptops when it
 * takes this update. Losing either would mean re-typing a patient's answers, so the
 * migration is exercised against a real version 1 database rather than assumed.
 *
 * The v1 database is built from the exported schema on disk, so this test cannot drift
 * away from the schema the shipped APK actually created. Room validates the migrated
 * result on open — a migration that produced the wrong columns fails here.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    @Test
    fun `migrating to per-laptop api keys keeps saved laptops and pending surveys`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = File(context.cacheDir, "migration-test.db").apply { delete() }
        createVersion1Database(dbFile)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            runBlocking {
                val endpoints = db.laptopEndpointDao().observeAll().first()
                assertEquals(1, endpoints.size)
                assertEquals("1번 X-ray실", endpoints[0].name)
                assertEquals("192.168.1.10", endpoints[0].host)
                assertEquals(18080, endpoints[0].port)
                // Blank means "use the key built into the APK" — exactly how this
                // endpoint behaved before the column existed.
                assertEquals("", endpoints[0].apiKey)

                val survey = db.surveyDao().getById("record-1")
                assertEquals("Ana", survey?.firstName)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `collapsing duplicate laptops keeps the one with a key and repoints its surveys`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = File(context.cacheDir, "migration-dedupe.db").apply { delete() }
        createVersion1Database(dbFile)

        // What a tablet looks like after the operator tapped Add twice and typed the
        // key into only one of the two cards, with a survey queued against the other.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("ALTER TABLE laptop_endpoints ADD COLUMN apiKey TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "INSERT INTO laptop_endpoints (id, name, host, port, apiKey, lastSuccessAt) "
                    + "VALUES ('laptop-2', '1번 X-ray실', '192.168.1.10', 18080, 'REAL-KEY', NULL)"
            )
            db.execSQL("UPDATE survey_records SET targetLaptopId = 'laptop-1' WHERE recordId = 'record-1'")
            db.version = 2
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            runBlocking {
                val endpoints = db.laptopEndpointDao().observeAll().first()
                val survivor = endpoints.single()
                // The row carrying a key wins: the blank duplicate would 401.
                assertEquals("REAL-KEY", survivor.apiKey)
                // A survey pointing at the deleted row would burn a retry attempt on
                // every pass and never send.
                assertEquals(survivor.id, db.surveyDao().getById("record-1")?.targetLaptopId)
            }
        } finally {
            db.close()
        }
    }

    /** Recreates what version 1 of the shipped app left on disk, schema and all. */
    private fun createVersion1Database(file: File) {
        val schema = JSONObject(
            File("schemas/com.medmission.survey.data.local.AppDatabase/1.json").readText()
        ).getJSONObject("database")

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(
                    entity.getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
            }

            // Room refuses to open a database it cannot identify, so the v1 identity
            // hash has to be here exactly as the shipped app wrote it.
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(schema.getString("identityHash")),
            )
            db.version = 1

            db.execSQL(
                "INSERT INTO laptop_endpoints (id, name, host, port, lastSuccessAt) "
                    + "VALUES ('laptop-1', '1번 X-ray실', '192.168.1.10', 18080, NULL)"
            )
            db.execSQL(
                "INSERT INTO survey_records (recordId, status, createdAt, sendAttempts, "
                    + "medicalHistory, symptoms, firstName) "
                    + "VALUES ('record-1', 'PENDING', 0, 1, '', '', 'Ana')"
            )
        } finally {
            db.close()
        }
    }
}
