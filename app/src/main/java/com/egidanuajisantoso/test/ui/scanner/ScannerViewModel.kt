package com.egidanuajisantoso.test.ui.scanner

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.egidanuajisantoso.test.data.ScannerRepository
import com.egidanuajisantoso.test.domain.DatasetSummary
import com.egidanuajisantoso.test.domain.PredictionLabel
import com.egidanuajisantoso.test.domain.ScanItemResult
import com.egidanuajisantoso.test.domain.ScanProgress
import com.egidanuajisantoso.test.service.FolderMonitorService
import com.egidanuajisantoso.test.storage.TreeUriStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScannerRepository(application.applicationContext)
    private val treeUriStore = TreeUriStore(application.applicationContext)
    private val appContext = application.applicationContext

    private var datasetTreeUri: Uri? = treeUriStore.loadDatasetTreeUri()

    private val _uiState = MutableStateFlow(
        ScannerUiState(
            datasetFolderLabel = datasetTreeUri?.let { it.toString() } ?: "Belum ada folder dataset",
            monitorPath = defaultMonitorPath(),
        )
    )
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    fun onDatasetFolderSelected(uri: Uri, displayName: String) {
        datasetTreeUri = uri
        treeUriStore.saveDatasetTreeUri(uri)
        _uiState.update {
            it.copy(
                datasetFolderLabel = displayName,
                infoMessage = "Folder dataset tersimpan",
                errorMessage = null,
            )
        }
    }

    fun onMonitorPathChanged(path: String) {
        _uiState.update { it.copy(monitorPath = path) }
    }

    fun scanSelectedFile(uri: Uri, displayName: String, pathHint: String? = displayName) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    progress = ScanProgress(0, 1, displayName),
                    infoMessage = "Memindai file...",
                    errorMessage = null,
                    singleScanResult = null,
                )
            }

            runCatching {
                repository.scanSingleFile(uri = uri, displayName = displayName, pathHint = pathHint)
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        progress = ScanProgress(1, 1, displayName),
                        singleScanResult = result,
                        datasetResults = listOf(result),
                        datasetSummary = buildSummaryFromSingle(result),
                        infoMessage = buildResultMessage(result),
                        errorMessage = null,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = throwable.message ?: "Gagal memindai file",
                        infoMessage = null,
                    )
                }
            }
        }
    }

    fun scanDatasetFolder() {
        val treeUri = datasetTreeUri
        if (treeUri == null) {
            _uiState.update {
                it.copy(errorMessage = "Pilih folder dataset terlebih dahulu")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    progress = ScanProgress(0, 0, "Memulai..."),
                    datasetResults = emptyList(),
                    datasetSummary = null,
                    singleScanResult = null,
                    infoMessage = "Memindai dataset...",
                    errorMessage = null,
                )
            }

            val results = mutableListOf<ScanItemResult>()
            runCatching {
                repository.scanTree(
                    treeUri = treeUri,
                    onProgress = { progress ->
                        _uiState.update { current -> current.copy(progress = progress) }
                    },
                    onItemResult = { result ->
                        results += result
                        _uiState.update { current -> current.copy(datasetResults = results.toList()) }
                    },
                )
            }.onSuccess { summary ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        datasetSummary = summary,
                        progress = ScanProgress(summary.totalFiles, summary.totalFiles, "Selesai"),
                        infoMessage = buildSummaryMessage(summary),
                        errorMessage = null,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = throwable.message ?: "Gagal memindai dataset",
                        infoMessage = null,
                    )
                }
            }
        }
    }

    fun startMonitor() {
        val path = _uiState.value.monitorPath.trim().ifBlank { defaultMonitorPath() }
        _uiState.update { it.copy(monitorStatus = "Memulai pemantauan...") }

        val intent = Intent(appContext, FolderMonitorService::class.java).apply {
            putExtra(FolderMonitorService.EXTRA_MONITOR_PATH, path)
        }
        ContextCompat.startForegroundService(appContext, intent)
        _uiState.update {
            it.copy(
                monitorStatus = "Monitoring aktif: $path",
                errorMessage = null,
            )
        }
    }

    fun stopMonitor() {
        val intent = Intent(appContext, FolderMonitorService::class.java)
        appContext.stopService(intent)
        _uiState.update { it.copy(monitorStatus = "Monitoring dihentikan") }
    }

    private fun defaultMonitorPath(): String {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return downloads.absolutePath
    }

    private fun buildSummaryFromSingle(result: ScanItemResult): DatasetSummary {
        val labeledFiles = if (result.expectedLabel == null) 0 else 1
        val correct = if (result.isCorrect == true) 1 else 0
        val malwareFiles = if (result.predicted.label == PredictionLabel.MALWARE) 1 else 0
        val safeFiles = if (result.predicted.label == PredictionLabel.SAFE) 1 else 0
        return DatasetSummary(
            totalFiles = 1,
            safeFiles = safeFiles,
            malwareFiles = malwareFiles,
            labeledFiles = labeledFiles,
            correctlyClassified = correct,
        )
    }

    private fun buildResultMessage(result: ScanItemResult): String {
        val percent = (result.predicted.confidence * 100).toInt()
        return "${result.displayName}: ${result.predicted.label.displayName()} ($percent%)"
    }

    private fun buildSummaryMessage(summary: DatasetSummary): String {
        val accuracy = summary.accuracyPercent?.let { "Akurasi ${it.toInt()}%" } ?: "Dataset tanpa label pembanding"
        return "Selesai: ${summary.totalFiles} file, $accuracy"
    }
}

data class ScannerUiState(
    val datasetFolderLabel: String = "Belum ada folder dataset",
    val monitorPath: String = "",
    val monitorStatus: String = "Monitor belum aktif",
    val isScanning: Boolean = false,
    val progress: ScanProgress? = null,
    val singleScanResult: ScanItemResult? = null,
    val datasetResults: List<ScanItemResult> = emptyList(),
    val datasetSummary: DatasetSummary? = null,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)

