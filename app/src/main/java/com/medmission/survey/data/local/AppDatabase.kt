package com.medmission.survey.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.model.SurveyRecord

@Database(
    entities = [SurveyRecord::class, LaptopEndpoint::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
    abstract fun laptopEndpointDao(): LaptopEndpointDao

    companion object {
        /**
         * Adds the per-laptop API key. Blank means "use the key built into the APK",
         * which is exactly how endpoints saved before this version behaved, so every
         * existing row keeps working and no survey waiting to be sent is lost.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE laptop_endpoints ADD COLUMN apiKey TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Collapses laptops saved more than once at the same address. Adding a
         * discovered laptop twice used to create a second row, and once keys became
         * per-laptop the duplicate could carry a blank one — the send then failed or
         * succeeded depending on which card the operator tapped.
         *
         * The row carrying a key wins, then the most recently used one. Surveys still
         * waiting to be sent are repointed at the survivor first: a dangling
         * targetLaptopId would burn a retry attempt on every pass and never recover.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TEMP TABLE endpoint_survivor AS
                    SELECT host, port, (
                        SELECT inner.id FROM laptop_endpoints AS inner
                        WHERE inner.host = outer.host AND inner.port = outer.port
                        ORDER BY (CASE WHEN inner.apiKey <> '' THEN 0 ELSE 1 END),
                                 COALESCE(inner.lastSuccessAt, 0) DESC,
                                 inner.rowid
                        LIMIT 1
                    ) AS keepId
                    FROM laptop_endpoints AS outer
                    GROUP BY host, port
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE survey_records SET targetLaptopId = (
                        SELECT s.keepId FROM laptop_endpoints AS e
                        JOIN endpoint_survivor AS s ON s.host = e.host AND s.port = e.port
                        WHERE e.id = survey_records.targetLaptopId
                    )
                    WHERE targetLaptopId IN (
                        SELECT id FROM laptop_endpoints
                        WHERE id NOT IN (SELECT keepId FROM endpoint_survivor)
                    )
                    """.trimIndent()
                )
                db.execSQL("DELETE FROM laptop_endpoints WHERE id NOT IN (SELECT keepId FROM endpoint_survivor)")
                db.execSQL("DROP TABLE endpoint_survivor")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_laptop_endpoints_host_port ON laptop_endpoints (host, port)")
            }
        }
        /**
         * The country an address was collected in. Nullable and defaulted to null:
         * every record written before the global form existed was collected in the
         * Philippines, and the bridge falls back to its own site setting when a payload
         * does not say, so backfilling a value here would only invent certainty.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE survey_records ADD COLUMN country TEXT")
            }
        }

    }
}
