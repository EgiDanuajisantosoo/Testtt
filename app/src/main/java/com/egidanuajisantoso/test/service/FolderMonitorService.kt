package com.egidanuajisantoso.test.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.egidanuajisantoso.test.data.ScannerRepository
import com.egidanuajisantoso.test.domain.PredictionLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FolderMonitorService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private lateinit var repository: ScannerRepository
    private var observer: FileObserver? = null
    private val debounceMap = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        repository = ScannerRepository(applicationContext)
        NotificationHelper.ensureChannels(this)
        startForeground(
            NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(
                context = this,
                monitoredPath = defaultMonitorPath(),
                message = "Menunggu file baru",
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val monitoredPath = intent?.getStringExtra(EXTRA_MONITOR_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: defaultMonitorPath()
        restartObserver(monitoredPath)
        updateForegroundNotification(monitoredPath, "Folder sedang dipantau")
        return START_STICKY
    }

    private fun restartObserver(path: String) {
        observer?.stopWatching()
        observer = null

        val folder = File(path)
        if (!folder.exists() || !folder.isDirectory) {
            updateForegroundNotification(path, "Folder tidak tersedia / tidak dapat diakses")
            return
        }

        observer = object : FileObserver(
            folder.absolutePath,
            CLOSE_WRITE or MOVED_TO or CREATE,
        ) {
            override fun onEvent(event: Int, relativePath: String?) {
                if (relativePath.isNullOrBlank()) return
                if (event and (CLOSE_WRITE or MOVED_TO or CREATE) == 0) return

                val watchedFile = File(folder, relativePath)
                if (!watchedFile.exists() || watchedFile.isDirectory) return
                if (shouldDebounce(watchedFile.absolutePath)) return

                serviceScope.launch {
                    delay(250)
                    if (!watchedFile.exists() || watchedFile.isDirectory) return@launch
                    runCatching {
                        repository.scanSingleFile(
                            uri = Uri.fromFile(watchedFile),
                            displayName = watchedFile.name ?: watchedFile.absolutePath,
                            pathHint = watchedFile.absolutePath,
                        )
                    }.onSuccess { result ->
                        if (result.predicted.label == PredictionLabel.MALWARE) {
                            NotificationHelper.showMalwareAlert(
                                context = this@FolderMonitorService,
                                scanResult = result,
                                filePath = watchedFile.absolutePath,
                            )
                        }
                    }
                }
            }
        }.also { it.startWatching() }
    }

    private fun shouldDebounce(path: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = debounceMap.put(path, now)
        return previous != null && now - previous < 2000L
    }

    private fun updateForegroundNotification(path: String, message: String) {
        runCatching {
            val notification = NotificationHelper.buildForegroundNotification(
                context = this,
                monitoredPath = path,
                message = message,
            )
            NotificationManagerCompat.from(this)
                .notify(NotificationHelper.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        observer?.stopWatching()
        observer = null
        serviceScope.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun defaultMonitorPath(): String {
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        return downloads.absolutePath
    }

    companion object {
        const val EXTRA_MONITOR_PATH = "extra_monitor_path"
    }
}



