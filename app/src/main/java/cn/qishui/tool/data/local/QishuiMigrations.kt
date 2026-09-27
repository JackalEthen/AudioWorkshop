package cn.qishui.tool.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `sample_rate_hz` INTEGER")
        db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `lyrics` TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `source_tracks` (
                `id` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `source_share_url` TEXT,
                `title` TEXT,
                `artist` TEXT,
                `local_path` TEXT,
                `format` TEXT,
                `duration_ms` INTEGER,
                `bitrate_bps` INTEGER,
                `size_bytes` INTEGER,
                `sample_rate_hz` INTEGER,
                `lyrics` TEXT,
                `file_hash` TEXT,
                `cover_uri` TEXT,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `edit_projects` (
                `id` TEXT NOT NULL,
                `source_track_id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `segments` TEXT NOT NULL,
                `gain_db` REAL,
                `fade_in_ms` INTEGER,
                `fade_out_ms` INTEGER,
                `lyric_offset_ms` INTEGER,
                `joined_track_ids` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `export_packages` (
                `edit_project_id` TEXT NOT NULL,
                `output_path` TEXT NOT NULL,
                `format` TEXT NOT NULL,
                `duration_ms` INTEGER NOT NULL,
                `size_bytes` INTEGER NOT NULL,
                `validation_status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`edit_project_id`, `output_path`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `source_tracks` ADD COLUMN `album` TEXT")
    }
}


val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `edit_projects` ADD COLUMN `join_transition` TEXT NOT NULL DEFAULT 'NORMAL'")
        db.execSQL("ALTER TABLE `edit_projects` ADD COLUMN `transition_ms` INTEGER NOT NULL DEFAULT 0")
    }
}