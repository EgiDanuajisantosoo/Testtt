package com.egidanuajisantoso.test.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.egidanuajisantoso.test.R
import com.egidanuajisantoso.test.domain.ScanItemResult

object NotificationHelper {
    const val CHANNEL_ID_MONITOR = "malware_monitor_channel"
    const val CHANNEL_ID_ALERT = "malware_alert_channel"
    const val FOREGROUND_NOTIFICATION_ID = 1001
    const val MALWARE_ALERT_NOTIFICATION_ID = 2001
    const val SCAN_ACTIVITY_NOTIFICATION_ID = 3001

    fun showScanActivityNotification(context: Context, fileName: String, result: ScanItemResult) {
        ensureChannels(context)
        val isSafe = result.predicted.label == com.egidanuajisantoso.test.domain.PredictionLabel.SAFE
        val statusText = if (isSafe) "Bersih (Clean)" else "Ditemukan Ancaman!"
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Pemindaian Real-time")
            .setContentText("$fileName: $statusText")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching {
                NotificationManagerCompat.from(context).notify(SCAN_ACTIVITY_NOTIFICATION_ID + fileName.hashCode(), notification)
            }
        }
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val monitorChannel = NotificationChannel(
            CHANNEL_ID_MONITOR,
            "Pemantauan Malware",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notifikasi status pemantauan folder"
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERT,
            "Peringatan Malware",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifikasi saat file terdeteksi berbahaya"
        }

        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(alertChannel)
    }

    fun buildForegroundNotification(
        context: Context,
        monitoredPath: String,
        message: String,
    ) = NotificationCompat.Builder(context, CHANNEL_ID_MONITOR)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Malware Monitor Aktif")
        .setContentText("$message: $monitoredPath")
        .setStyle(NotificationCompat.BigTextStyle().bigText("$message: $monitoredPath"))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    fun buildMalwareAlert(
        context: Context,
        scanResult: ScanItemResult,
        filePath: String,
    ) = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Bahaya Tinggi: Malware Terdeteksi")
        .setContentText(scanResult.displayName)
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(
                    "File: ${scanResult.displayName}\n" +
                        "Path: $filePath\n" +
                        "Prediksi: ${scanResult.predicted.label.displayName()} (${(scanResult.predicted.confidence * 100).toInt()}%)"
                )
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    fun showMalwareAlert(
        context: Context,
        scanResult: ScanItemResult,
        filePath: String,
    ) {
        ensureChannels(context)
        runCatching {
            NotificationManagerCompat.from(context).notify(
                MALWARE_ALERT_NOTIFICATION_ID,
                buildMalwareAlert(context, scanResult, filePath),
            )
        }
    }
}
