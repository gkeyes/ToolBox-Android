package io.toolbox.host.importflow

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Opens the system picker without adding an intermediate navigation destination. */
@Composable
internal fun rememberPackageImportPicker(
    viewModel: ImportViewModel,
    contentResolver: ContentResolver,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val inputFactory = remember(contentResolver) { ContentResolverPackageInputFactory(contentResolver) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var resolvingPackage by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ToolBoxOpenDocument.contract) { uri ->
        pickerOpen = false
        // Cancelling the picker leaves the current page and import result untouched.
        if (uri != null) {
            resolvingPackage = true
            scope.launch {
                try {
                    when (val source = inputFactory.fromPickerResult(uri)) {
                        SelectedPackageSource.Cancelled -> Unit
                        is SelectedPackageSource.Ready -> viewModel.importPackage(source.input)
                        is SelectedPackageSource.Rejected -> viewModel.pickerRejected(source.message)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    viewModel.pickerRejected("无法读取所选文件，请重新选择 .tbx 工具包。")
                } finally {
                    resolvingPackage = false
                }
            }
        }
    }
    return {
        // Read the latest state at click time; recomposition must not be required
        // to prevent a second picker or replace a pending version confirmation.
        if (viewModel.state.value.canRequestPackage(pickerOpen || resolvingPackage)) {
            pickerOpen = true
            try {
                picker.launch(ToolBoxOpenDocument.mimeTypes())
            } catch (_: ActivityNotFoundException) {
                pickerOpen = false
                viewModel.pickerRejected("无法打开系统文件选择器，请检查文件管理应用是否可用。")
            } catch (_: SecurityException) {
                pickerOpen = false
                viewModel.pickerRejected("系统未允许打开文件选择器，请稍后重试。")
            }
        }
    }
}

internal fun ImportUiState.canRequestPackage(pickerBusy: Boolean): Boolean =
    !pickerBusy && !working && confirmation == null
