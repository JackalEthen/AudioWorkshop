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

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `edit_projects` ADD COLUMN `fade_curve` TEXT NOT NULL DEFAULT 'LINEAR'")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `edit_projects` ADD COLUMN `lyrics_override` TEXT")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `edit_projects` ADD COLUMN `normalize_sources` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `edit_projects` ADD COLUMN `trailing_silence_ms` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 播放器上线：本地歌和 lx 音源的曲目统一放 source_tracks，
 * 靠 source_code 区分来源、platform_song_id 存平台歌曲 id（音源求 URL 必需）。
 * 收藏是单张星标表，不做歌单。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `source_tracks` ADD COLUMN `source_code` TEXT")
        db.execSQL("ALTER TABLE `source_tracks` ADD COLUMN `platform_song_id` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `favorites` (" +
                "`song_id` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`song_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_created_at` ON `favorites` (`created_at`)")
    }
}

/**
 * 导入的 lx 自定义源。
 *
 * 可以导入多个，但只能选一个，所以这里不留多行的部分唯一索引
 * （SQLite 的部分索引 Room 不认），唯一性由 [MusicSourceDao.selectOnly] 在事务里清其余行。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `music_sources` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `author` TEXT NOT NULL,
                `version` TEXT NOT NULL,
                `homepage` TEXT NOT NULL,
                `script` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `selected` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `last_error` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        // 索引必须和 @Entity(indices = ...) 一一对应：实体声明了迁移就必须建，
        // 迁移建了实体也必须声明，否则 Room 校验失败、App 启动即崩。
        // favorites 那张表就是这个写法。
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_music_sources_created_at` ON `music_sources` (`created_at`)")
    }
}