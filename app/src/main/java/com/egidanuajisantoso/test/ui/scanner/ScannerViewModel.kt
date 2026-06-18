package com.egidanuajisantoso.test.ui.scanner

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.egidanuajisantoso.test.data.ScannerRepository
import com.egidanuajisantoso.test.domain.ScanItemResult
import com.egidanuajisantoso.test.domain.ScanProgress
import com.egidanuajisantoso.test.domain.ScanResultBus
import com.egidanuajisantoso.test.service.FolderMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScannerRepository(application.applicationContext)
    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences("scanner_prefs", android.content.Context.MODE_PRIVATE)

    private var fullScanJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(
        ScannerUiState(
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
                        historyResults = (listOf(result) + current.historyResults).take(100).distinctBy { it.uri },
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

    fun scanSelectedFile(uri: Uri, displayName: String, pathHint: String? = displayName) {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isScanning = true,
                    progress = ScanProgress(0, 1, displayName),
                    infoMessage = "Scanning file...",
                    errorMessage = null,
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
                        historyResults = (listOf(result) + it.historyResults).take(100).distinctBy { it.uri },
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
                        errorMessage = throwable.message ?: "Failed to scan file",
                        infoMessage = null,
                    )
                }
            }
        }
    }

    fun scanSpecificFolder(treeUri: Uri, displayName: String) {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isScanning = true,
                    progress = ScanProgress(0, 0, displayName),
                    infoMessage = "Scanning folder...",
                    errorMessage = null,
                )
            }

            runCatching {
                repository.scanTree(
                    treeUri = treeUri,
                    onProgress = { progress ->
                        _uiState.update { it.copy(progress = progress) }
                    },
                    onItemResult = { result ->
                        _uiState.update { current ->
                            current.copy(
                                historyResults = (listOf(result) + current.historyResults).take(100).distinctBy { it.uri }
                            )
                        }
                    }
                )
            }.onSuccess { summary ->
                val duration = System.currentTimeMillis() - startTime
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        infoMessage = "Scan complete: ${summary.totalFiles} files scanned.",
                        errorMessage = null,
                        lastCheckedTime = System.currentTimeMillis(),
                        lastScanDurationMillis = duration
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = throwable.message ?: "Failed to scan folder",
                        infoMessage = null,
                    )
                }
            }
        }
    }

    fun startFullDeviceScan() {
        _uiState.update { 
            it.copy(
                currentScreen = ScannerScreenType.FULL_SCAN,
                isFullScanning = false,
                fullScanResults = emptyList(),
                fullScanProgress = null,
                fullScanStartTime = null
            ) 
        }
    }

    fun performRealFullDeviceScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            _uiState.update { it.copy(errorMessage = "'All Files Access' permission is required for full scan.") }
            return
        }

        val rootPath = Environment.getExternalStorageDirectory()
        
        _uiState.update { 
            it.copy(
                isFullScanning = true, 
                fullScanResults = emptyList(),
                fullScanProgress = null,
                fullScanStartTime = System.currentTimeMillis()
            ) 
        }

        fullScanJob?.cancel()
        fullScanJob = viewModelScope.launch {
            val results = mutableListOf<ScanItemResult>()
            
            runCatching {
                repository.scanFullFileSystem(
                    rootFile = rootPath,
                    isTrainingMode = false,
                    onProgress = { progress ->
                        _uiState.update { current -> current.copy(fullScanProgress = progress) }
                    },
                    onItemResult = { result ->
                        results += result
                        _uiState.update { current -> 
                            current.copy(
                                fullScanResults = results.toList(),
                                historyResults = (listOf(result) + current.historyResults).take(100).distinctBy { it.uri }
                            ) 
                        }
                    },
                )
            }.onSuccess { summary ->
                _uiState.update {
                    it.copy(
                        isFullScanning = false,
                        fullScanProgress = ScanProgress(summary.totalFiles, summary.totalFiles, "Complete"),
                        lastCheckedTime = System.currentTimeMillis(),
                        infoMessage = "Full scan complete."
                    )
                }
            }.onFailure { throwable ->
                if (throwable is kotlinx.coroutines.CancellationException) return@launch
                _uiState.update {
                    it.copy(
                        isFullScanning = false,
                        errorMessage = throwable.message ?: "Failed to scan device"
                    )
                }
            }
        }
    }

    fun stopFullScan() {
        fullScanJob?.cancel()
        fullScanJob = null
        _uiState.update { it.copy(currentScreen = ScannerScreenType.DASHBOARD, isFullScanning = false) }
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
                    monitorStatus = "Monitoring active: $path",
                    isMonitorRunning = true,
                    errorMessage = null,
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    monitorStatus = "Failed to start monitor",
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
                monitorStatus = "Monitoring stopped",
                isMonitorRunning = false
            ) 
        }
    }

    private fun defaultMonitorPath(): String {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return downloads.absolutePath
    }

    private fun buildResultMessage(result: ScanItemResult): String {
        val percent = (result.predicted.confidence * 100).toInt()
        return "${result.displayName}: ${result.predicted.label.name} ($percent%)"
    }
}

data class ScannerUiState(
    val currentScreen: ScannerScreenType = ScannerScreenType.DASHBOARD,
    val historyFilter: HistoryFilter = HistoryFilter.FOUND,
    val monitorPath: String = "",
    val monitorStatus: String = "Monitor inactive",
    val isMonitorRunning: Boolean = false,
    val isScanning: Boolean = false,
    val isFullScanning: Boolean = false,
    val progress: ScanProgress? = null,
    val fullScanProgress: ScanProgress? = null,
    val historyResults: List<ScanItemResult> = emptyList(),
    val fullScanResults: List<ScanItemResult> = emptyList(),
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val lastCheckedTime: Long? = null,
    val lastScanDurationMillis: Long? = null,
    val fullScanStartTime: Long? = null,
)

enum class ScannerScreenType {
    DASHBOARD,
    HISTORY,
    SETTINGS,
    FULL_SCAN
}

enum class HistoryFilter {
    FOUND,
    CLEAN
}
