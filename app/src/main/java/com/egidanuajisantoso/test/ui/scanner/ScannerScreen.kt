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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.egidanuajisantoso.test.domain.BinaryImagePreprocessor
import com.egidanuajisantoso.test.domain.OnnxMalwareClassifier
import com.egidanuajisantoso.test.domain.PredictionLabel
import com.egidanuajisantoso.test.domain.ScanItemResult
import com.egidanuajisantoso.test.domain.ScanProgress
import com.egidanuajisantoso.test.domain.finalLabel

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
    
    // Diagnostic toggle state
    var showDiagnostics by remember { mutableStateOf(false) }

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
                    text = "Mobile Shield - Malware Scanner",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Pemindai lokal offline: scan file manual dan proteksi realtime pada folder unduhan menggunakan model ONNX dari assets.",
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
                            Button(onClick = { showDiagnostics = !showDiagnostics }) {
                                Text(if (showDiagnostics) "Hide Diagnostics" else "Diagnostics")
                            }
                        }
                    }
                }
            }
            
            item {
                if (showDiagnostics) {
                    DiagnosticsCard()
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
            
            // Display raw logits if available
            if (result.predicted.rawScores.isNotEmpty()) {
                Text("Logits: ${formatLogits(result.predicted.rawScores)}", style = MaterialTheme.typography.bodySmall)
            }
            
            // Display probabilities in English format matching ML output
            Text("Probability benign: ${formatPercent(result.predicted.safeProbability)}")
            Text("Probability malware: ${formatPercent(result.predicted.malwareProbability)}")
            
            // Display prediction in English
            val finalLabel = result.predicted.finalLabel()
            val predictionText = when (finalLabel) {
                PredictionLabel.SAFE -> "benign"
                PredictionLabel.MALWARE -> "malware"
            }
            Text("Prediction: $predictionText", fontWeight = FontWeight.SemiBold)
            
            result.expectedLabel?.let { expected ->
                Text("Ekspektasi: ${expected.displayName()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResultItemCard(result: ScanItemResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val itemFinalLabel = result.predicted.finalLabel()
                val predictionText = when (itemFinalLabel) {
                    PredictionLabel.SAFE -> "benign"
                    PredictionLabel.MALWARE -> "malware"
                }
                AssistChip(
                    onClick = {},
                    label = { Text(predictionText) },
                )
                result.expectedLabel?.let {
                    AssistChip(onClick = {}, label = { Text("Expected: ${it.displayName()}") })
                }
            }
            Text(result.displayName, fontWeight = FontWeight.SemiBold)
            
            // Display raw logits if available
            if (result.predicted.rawScores.isNotEmpty()) {
                Text("Logits: ${formatLogits(result.predicted.rawScores)}", style = MaterialTheme.typography.bodySmall)
            }
            
            // Display probabilities in ML output format
            Text("Probability benign: ${formatPercent(result.predicted.safeProbability)}")
            Text("Probability malware: ${formatPercent(result.predicted.malwareProbability)}")
            Text("Confidence: ${formatPercent(result.predicted.confidence)}")
            Text("Source: ${result.sourceHint ?: "-"}")
            Text(result.isCorrect?.let { if (it) "Matches label" else "Incorrect" } ?: "Label not available")
        }
        HorizontalDivider()
    }
}

@Composable
private fun DiagnosticsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Model Configuration (malware_model_binary.onnx)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            
            // Output Index Display (fixed now)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Output Index Malware: ${BinaryImagePreprocessor.outputIndexMalware} (FIXED)", Modifier.weight(1f))
                Text("✅", style = MaterialTheme.typography.bodySmall)
            }
            
            // ImageNet Normalization Status (mandatory)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("ImageNet Normalization: ON (MANDATORY)", Modifier.weight(1f))
                Text("✅", style = MaterialTheme.typography.bodySmall)
            }
            
            Text("mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]", 
                style = MaterialTheme.typography.labelSmall)
            
            // Optional Tuning
            Text("Optional Tuning:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            
            PreprocessingToggle("Use Bitmap Decode (for .png/.jpg)", BinaryImagePreprocessor.useBitmapDecodeForImages) { 
                BinaryImagePreprocessor.useBitmapDecodeForImages = it 
            }
            PreprocessingToggle("Centered Normalization (-1..1)", BinaryImagePreprocessor.useCenteredNormalization) {
                BinaryImagePreprocessor.useCenteredNormalization = it
            }
            PreprocessingToggle("BGR Order", BinaryImagePreprocessor.useBgr) {
                BinaryImagePreprocessor.useBgr = it
            }
            
            Text("⚠️ Critical settings (output index, ImageNet norm) are FIXED for model accuracy.", 
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PreprocessingToggle(label: String, value: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        AssistChip(
            onClick = { onValueChange(!value) },
            label = { Text(if (value) "ON" else "OFF") },
        )
    }
}

private fun progressText(progress: ScanProgress): String {
    val percent = if (progress.total <= 0) 0 else ((progress.completed.toFloat() / progress.total.toFloat()) * 100).toInt()
    return "${progress.completed}/${progress.total} file ($percent%) - ${progress.currentFileName}"
}

private fun formatPercent(value: Float): String = "${(value * 100f).toInt()}%"

private fun formatLogits(scores: FloatArray): String {
    if (scores.isEmpty()) return "[]"
    val formatted = scores.joinToString(", ") { "%.7g".format(it) }
    return "[ $formatted ]"
}

private fun persistReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
