package io.toolbox.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object ToolBoxMigrations {
    val ONE_TO_TWO = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS index_background_tasks_toolId")
            db.execSQL("DROP INDEX IF EXISTS index_background_tasks_state")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_background_tasks_toolId_state ON background_tasks (toolId, state)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_background_tasks_toolId_createdAt_taskId ON background_tasks (toolId ASC, createdAt DESC, taskId ASC)")
        }
    }
}
