package com.egidanuajisantoso.test.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.egidanuajisantoso.test.domain.finalLabel
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
    private lateinit var repository: com.egidanuajisantoso.test.data.ScannerRepository
    private var observer: FileObserver? = null
    private val debounceMap = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        repository = com.egidanuajisantoso.test.data.ScannerRepository(applicationContext)
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
                        // Follow flowchart: trigger alert when malware probability > threshold
                        if (result.predicted.finalLabel() == PredictionLabel.MALWARE) {
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

    @SuppressLint("MissingPermission")
    private fun updateForegroundNotification(path: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val notification = NotificationHelper.buildForegroundNotification(
                context = this,
                monitoredPath = path,
                message = message,
            )
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(NotificationHelper.FOREGROUND_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission might be revoked while service is running.
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



