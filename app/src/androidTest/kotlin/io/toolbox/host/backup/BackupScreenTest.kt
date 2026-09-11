package io.toolbox.host.backup

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.MainActivity
import io.toolbox.host.settings.SettingsContent
import io.toolbox.host.settings.SettingsUiState
import io.toolbox.tool.packagekit.backup.BackupContents
import io.toolbox.tool.packagekit.backup.PreparedBackup
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsEntryOpensBackupWithoutChangingToolHtml() {
        var clicked = false
        compose.activity.setContent { ToolBoxTheme {
            SettingsContent(SettingsUiState(loaded = true), PaddingValues(), {}, {}, {}, {}, onBackupRestore = { clicked = true })
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings_backup_restore"))
        compose.onNodeWithTag("settings_backup_restore").performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }
    @Test fun exportRestoreProgressAndResultAreVisibleAndActionable() {
        val state = mutableStateOf<BackupUiState>(BackupUiState.Idle)
        var export = 0; var restore = 0
        compose.activity.setContent { ToolBoxTheme { BackupContent(state.value, {}, { export++ }, { restore++ }, {}, {}, {}) } }
        compose.onNodeWithTag("backup_page").performScrollToNode(hasTestTag("backup_export"))
        compose.onNodeWithTag("backup_export").performClick()
        compose.onNodeWithTag("backup_page").performScrollToNode(hasTestTag("backup_restore"))
        compose.onNodeWithTag("backup_restore").performClick()
        compose.runOnIdle { assertEquals(1, export); assertEquals(1, restore); state.value = BackupUiState.Progress("校验", 50) }
        compose.onNodeWithTag("backup_page").performScrollToNode(hasTestTag("backup_progress"))
        compose.onNodeWithTag("backup_progress").assertExists()
        compose.runOnIdle { state.value = BackupUiState.Result("恢复已完成", "请重新打开工具") }
        compose.onNodeWithTag("backup_page").performScrollToNode(hasTestTag("backup_result"))
        compose.onNodeWithTag("backup_result").assertTextEquals("恢复已完成")
    }
    @Test fun conflictsAndRunningTasksRequireExplicitConfirmation() {
        val root = File(compose.activity.cacheDir, "backup-ui-fixture").apply { mkdirs() }
        val preview = RestorePreview(PreparedBackup(root, BackupContents("0.7.6", 1, emptyList())), listOf(RestoreToolPlan("io.toolbox.fixture", "Fixture", "2.0", "1.0")), emptyMap(), setOf("io.toolbox.fixture"), setOf("task"), emptyList())
        var confirmed = 0
        compose.activity.setContent { ToolBoxTheme { BackupContent(BackupUiState.ConfirmRestore(preview), {}, {}, {}, {}, { confirmed++ }, {}) } }
        compose.onNodeWithTag("backup_page").performScrollToNode(hasTestTag("backup_conflicts"))
        compose.onNodeWithTag("backup_conflicts").assertTextContains("1 个与本机冲突", substring = true)
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithTag("backup_page").performScrollToNode(hasTestTag("backup_confirm_restore"))
        compose.onNodeWithTag("backup_confirm_restore").performClick()
        compose.runOnIdle { assertEquals(1, confirmed); root.deleteRecursively() }
    }
    @Test fun documentContractsUseSafLocalOnlyAndAcceptCancellation() {
        val create = CreateBackupDocument(); val open = OpenBackupDocument()
        val write = create.createIntent(compose.activity, "backup.zip")
        val read = open.createIntent(compose.activity, arrayOf("application/zip"))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, write.action)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, read.action)
        assertTrue(write.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)); assertTrue(read.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertNull(create.parseResult(Activity.RESULT_CANCELED, null)); assertNull(open.parseResult(Activity.RESULT_CANCELED, null))
    }
}
