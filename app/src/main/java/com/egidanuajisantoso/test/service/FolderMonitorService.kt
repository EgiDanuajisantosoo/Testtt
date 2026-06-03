package com.egidanuajisantoso.test.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.egidanuajisantoso.test.domain.PredictionLabel
import com.egidanuajisantoso.test.domain.ScanResultBus
import com.egidanuajisantoso.test.domain.finalLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class FolderMonitorService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private lateinit var repository: com.egidanuajisantoso.test.data.ScannerRepository
    
    private var contentObserver: ContentObserver? = null
    private val processedIds = ConcurrentHashMap<Long, Long>()

    override fun onCreate() {
        super.onCreate()
        repository = com.egidanuajisantoso.test.data.ScannerRepository(applicationContext)
        NotificationHelper.ensureChannels(this)
        startForeground(
            NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(
                context = this,
                monitoredPath = "Seluruh Penyimpanan",
                message = "Perlindungan Real-time Aktif",
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startGlobalFileObserver()
        return START_STICKY
    }

    private fun startGlobalFileObserver() {
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Monitor perubahan pada tabel Files (mencakup semua jenis file)
                serviceScope.launch {
                    // Jeda singkat agar sistem selesai menulis file ke storage
                    delay(2000)
                    scanLatestFiles()
                }
            }
        }

        // Daftarkan observer untuk seluruh volume penyimpanan eksternal
        contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,
            contentObserver!!
        )
    }

    private suspend fun scanLatestFiles() {
        runCatching {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATE_ADDED
            )
            
            // Ambil 5 file terbaru yang ditambahkan ke sistem
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            val queryUri = MediaStore.Files.getContentUri("external")
            
            contentResolver.query(queryUri, projection, null, null, sortOrder)?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < 5) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    val path = cursor.getString(2)
                    val dateAdded = cursor.getLong(3)
                    
                    // Cek jika file ini benar-benar baru (dalam 1 menit terakhir) dan belum diproses
                    val nowSeconds = System.currentTimeMillis() / 1000
                    if (nowSeconds - dateAdded < 60 && !processedIds.containsKey(id)) {
                        processedIds[id] = System.currentTimeMillis()
                        
                        val itemUri = ContentUris.withAppendedId(queryUri, id)
                        val result = repository.scanSingleFile(
                            uri = itemUri,
                            displayName = name ?: "File Baru",
                            pathHint = path
                        )
                        
                        ScanResultBus.emitResult(result)
                        NotificationHelper.showScanActivityNotification(this, name ?: "File", result)
                        
                        if (result.predicted.finalLabel() == PredictionLabel.MALWARE) {
                            NotificationHelper.showMalwareAlert(this, result, path ?: name ?: "Unknown")
                        }
                    }
                    count++
                }
            }
            
            // Bersihkan map cache ID yang sudah lama (> 10 menit) agar memori tetap hemat
            val tenMinutesAgo = System.currentTimeMillis() - 600_000
            processedIds.entries.removeIf { it.value < tenMinutesAgo }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateForegroundNotification(path: String, message: String) {
        try {
            val notification = NotificationHelper.buildForegroundNotification(this, path, message)
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(NotificationHelper.FOREGROUND_NOTIFICATION_ID, notification)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        serviceScope.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_MONITOR_PATH = "extra_monitor_path"
    }
}
