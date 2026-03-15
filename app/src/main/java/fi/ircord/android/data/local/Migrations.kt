package fi.ircord.android.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations for IrcordDatabase.
 */
object Migrations {
    
    /**
     * Migration from version 1 to 2:
     * - Adds public_key column to peer_identities table for native store compatibility
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add public_key column for native store Base64 encoded keys
            db.execSQL("ALTER TABLE peer_identities ADD COLUMN public_key TEXT DEFAULT NULL")
        }
    }
    
    /**
     * Migration from version 2 to 3:
     * - Adds topic column to channels table
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE channels ADD COLUMN topic TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 3 to 4:
     * - Adds presence_status and presence_updated_at columns to peer_identities table
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE peer_identities ADD COLUMN presence_status TEXT DEFAULT 'offline'")
            db.execSQL("ALTER TABLE peer_identities ADD COLUMN presence_updated_at INTEGER DEFAULT 0")
        }
    }

    /**
     * All migrations in order.
     */
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
    )
}
