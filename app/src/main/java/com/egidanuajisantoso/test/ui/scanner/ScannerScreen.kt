package com.egidanuajisantoso.test.ui.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.egidanuajisantoso.test.domain.PredictionLabel
import com.egidanuajisantoso.test.domain.ScanItemResult
import com.egidanuajisantoso.test.domain.ScanProgress
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var startMonitorAfterPermission by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                persistReadPermission(context, uri)
                val displayName = resolveDisplayName(context, uri)
                viewModel.scanSelectedFile(uri = uri, displayName = displayName, pathHint = displayName)
            }
        },
    )

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                persistTreePermission(context, uri)
                val displayName = resolveDisplayName(context, uri)
                viewModel.onDatasetFolderSelected(uri, displayName)
            }
        },
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (startMonitorAfterPermission) {
                    startMonitorAfterPermission = false
                    viewModel.startMonitor()
                }
            }
        },
    )

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Malware Scanner & Monitor",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Scan file satuan, dataset folder, dan pantau folder unduhan dengan model ONNX dari assets.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Fitur 1 - Scanner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            }) {
                                Text("Pilih File")
                            }
                            Button(onClick = {
                                folderPickerLauncher.launch(null)
                            }) {
                                Text("Pilih Folder Dataset")
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.scanDatasetFolder() },
                            enabled = !state.isScanning,
                        ) {
                            Text("Scan Dataset")
                        }
                        Text("Folder dataset: ${state.datasetFolderLabel}")
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Fitur 2 - Background Monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Monitoring berbasis FileObserver bekerja paling baik pada folder yang bisa diakses aplikasi. Default: Downloads.")
                        OutlinedTextField(
                            value = state.monitorPath,
                            onValueChange = viewModel::onMonitorPathChanged,
                            label = { Text("Path folder yang dipantau") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.POST_NOTIFICATIONS,
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    viewModel.startMonitor()
                                } else {
                                    startMonitorAfterPermission = true
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }) {
                                Text("Mulai Monitor")
                            }
                            OutlinedButton(onClick = viewModel::stopMonitor) {
                                Text("Hentikan Monitor")
                            }
                        }
                        Text(state.monitorStatus)
                    }
                }
            }

            item {
                if (state.isScanning) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Proses pemindaian sedang berjalan...")
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            state.progress?.let { progress ->
                                Text(progressText(progress))
                            }
                        }
                    }
                }
            }

            item {
                state.infoMessage?.let { info ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Text(
                            text = info,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                state.singleScanResult?.let { result ->
                    ResultSummaryCard(title = "Hasil File Terakhir", result = result)
                }
            }

            item {
                state.datasetSummary?.let { summary ->
                    SummaryCard(summary = summary)
                }
            }

            item {
                Text("Daftar hasil realtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            items(state.datasetResults) { result ->
                ResultItemCard(result = result)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: com.egidanuajisantoso.test.domain.DatasetSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ringkasan Dataset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Total file: ${summary.totalFiles}")
            Text("Aman: ${summary.safeFiles}")
            Text("Malware: ${summary.malwareFiles}")
            Text("File berlabel: ${summary.labeledFiles}")
            Text("Benar: ${summary.correctlyClassified}")
            Text(summary.accuracyPercent?.let { "Akurasi: ${it.toInt()}%" } ?: "Akurasi: tidak tersedia (dataset tanpa label folder)")
        }
    }
}

@Composable
private fun ResultSummaryCard(
    title: String,
    result: ScanItemResult,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("File: ${result.displayName}")
            Text("Prediksi: ${result.predicted.label.displayName()} (${formatPercent(result.predicted.confidence)})")
            Text("Aman: ${formatPercent(result.predicted.safeProbability)}")
            Text("Malware: ${formatPercent(result.predicted.malwareProbability)}")
            result.expectedLabel?.let { expected ->
                Text("Label folder: ${expected.displayName()}")
            }
        }
    }
}

@Composable
private fun ResultItemCard(result: ScanItemResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(result.predicted.label.displayName()) },
                )
                result.expectedLabel?.let {
                    AssistChip(onClick = {}, label = { Text("Ekspektasi: ${it.displayName()}") })
                }
            }
            Text(result.displayName, fontWeight = FontWeight.SemiBold)
            Text("Confidence: ${formatPercent(result.predicted.confidence)}")
            Text("Safe: ${formatPercent(result.predicted.safeProbability)} | Malware: ${formatPercent(result.predicted.malwareProbability)}")
            Text("Sumber: ${result.sourceHint ?: "-"}")
            Text(result.isCorrect?.let { if (it) "Sesuai label" else "Tidak sesuai label" } ?: "Label pembanding tidak tersedia")
        }
        HorizontalDivider()
    }
}

private fun progressText(progress: ScanProgress): String {
    val percent = if (progress.total <= 0) 0 else ((progress.completed.toFloat() / progress.total.toFloat()) * 100).toInt()
    return "${progress.completed}/${progress.total} file ($percent%) - ${progress.currentFileName}"
}

private fun formatPercent(value: Float): String = "${(value * 100f).toInt()}%"

private fun persistReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun persistTreePermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
}

private fun PredictionLabel.displayName(): String = when (this) {
    PredictionLabel.SAFE -> "Aman"
    PredictionLabel.MALWARE -> "Malware"
}



