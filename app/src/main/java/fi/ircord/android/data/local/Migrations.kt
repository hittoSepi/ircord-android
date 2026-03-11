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
}
