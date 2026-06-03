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
import com.egidanuajisantoso.test.domain.ScanResultBus
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
    private val prefs = appContext.getSharedPreferences("scanner_prefs", android.content.Context.MODE_PRIVATE)

    private var datasetTreeUri: Uri? = treeUriStore.loadDatasetTreeUri()

    private val _uiState = MutableStateFlow(
        ScannerUiState(
            datasetFolderLabel = datasetTreeUri?.let { it.toString() } ?: "Belum ada folder dataset",
            monitorPath = defaultMonitorPath(),
            isMonitorRunning = isServiceRunning()
        )
    )
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ScanResultBus.events.collect { result ->
                _uiState.update { current ->
                    current.copy(
                        datasetResults = (listOf(result) + current.datasetResults).take(50).distinctBy { it.uri },
                        lastCheckedTime = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = appContext.getSystemService(android.app.ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (FolderMonitorService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun navigateTo(screen: ScannerScreenType) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun setHistoryFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(historyFilter = filter) }
    }

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
            val startTime = System.currentTimeMillis()
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
                val duration = System.currentTimeMillis() - startTime
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        progress = ScanProgress(1, 1, displayName),
                        singleScanResult = result,
                        datasetResults = (listOf(result) + it.datasetResults).take(50).distinctBy { it.uri },
                        datasetSummary = buildSummaryFromSingle(result),
                        infoMessage = buildResultMessage(result),
                        errorMessage = null,
                        lastCheckedTime = System.currentTimeMillis(),
                        lastScanDurationMillis = duration
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
        scanSpecificFolder(treeUri, "Dataset Folder")
    }

    fun scanSpecificFolder(treeUri: Uri, displayName: String) {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isScanning = true,
                    progress = ScanProgress(0, 0, "Memulai..."),
                    datasetResults = emptyList(),
                    datasetSummary = null,
                    singleScanResult = null,
                    infoMessage = "Memindai folder: $displayName",
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
                        _uiState.update { current -> 
                            current.copy(datasetResults = (results.toList() + current.datasetResults).take(50).distinctBy { it.uri }) 
                        }
                    },
                )
            }.onSuccess { summary ->
                val duration = System.currentTimeMillis() - startTime
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        datasetSummary = summary,
                        progress = ScanProgress(summary.totalFiles, summary.totalFiles, "Selesai"),
                        infoMessage = buildSummaryMessage(summary),
                        errorMessage = null,
                        lastCheckedTime = System.currentTimeMillis(),
                        lastScanDurationMillis = duration
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = throwable.message ?: "Gagal memindai folder",
                        infoMessage = null,
                    )
                }
            }
        }
    }

    fun startMonitor() {
        prefs.edit().putBoolean("monitor_enabled", true).apply()
        val path = _uiState.value.monitorPath.trim().ifBlank { defaultMonitorPath() }

        val intent = Intent(appContext, FolderMonitorService::class.java).apply {
            putExtra(FolderMonitorService.EXTRA_MONITOR_PATH, path)
        }
        
        try {
            ContextCompat.startForegroundService(appContext, intent)
            _uiState.update {
                it.copy(
                    monitorStatus = "Monitoring aktif: $path",
                    isMonitorRunning = true,
                    errorMessage = null,
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    monitorStatus = "Gagal memulai monitor",
                    isMonitorRunning = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun stopMonitor() {
        prefs.edit().putBoolean("monitor_enabled", false).apply()
        val intent = Intent(appContext, FolderMonitorService::class.java)
        appContext.stopService(intent)
        _uiState.update { 
            it.copy(
                monitorStatus = "Monitoring dihentikan",
                isMonitorRunning = false
            ) 
        }
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
    val currentScreen: ScannerScreenType = ScannerScreenType.DASHBOARD,
    val historyFilter: HistoryFilter = HistoryFilter.FOUND,
    val datasetFolderLabel: String = "Belum ada folder dataset",
    val monitorPath: String = "",
    val monitorStatus: String = "Monitor belum aktif",
    val isMonitorRunning: Boolean = false,
    val isScanning: Boolean = false,
    val progress: ScanProgress? = null,
    val singleScanResult: ScanItemResult? = null,
    val datasetResults: List<ScanItemResult> = emptyList(),
    val datasetSummary: DatasetSummary? = null,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val lastCheckedTime: Long? = null,
    val lastScanDurationMillis: Long? = null,
)

enum class ScannerScreenType {
    DASHBOARD,
    HISTORY,
    SETTINGS
}

enum class HistoryFilter {
    FOUND,
    CLEAN
}
