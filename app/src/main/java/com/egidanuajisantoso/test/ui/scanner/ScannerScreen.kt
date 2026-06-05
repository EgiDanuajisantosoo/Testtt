package com.egidanuajisantoso.test.ui.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.egidanuajisantoso.test.domain.PredictionLabel
import com.egidanuajisantoso.test.domain.ScanItemResult
import com.egidanuajisantoso.test.domain.ScanProgress
import com.egidanuajisantoso.test.domain.finalLabel
import com.egidanuajisantoso.test.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    
    // Auto-resume monitor if returning from settings with permission
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (android.os.Environment.isExternalStorageManager()) {
                        val prefs = context.getSharedPreferences("scanner_prefs", android.content.Context.MODE_PRIVATE)
                        if (prefs.getBoolean("monitor_enabled", false) && !state.isMonitorRunning) {
                            viewModel.startMonitor()
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var startMonitorAfterPermission by remember { mutableStateOf(false) }
    var showQuickScanOptions by remember { mutableStateOf(false) }

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
                persistReadPermission(context, uri)
                val displayName = resolveDisplayName(context, uri)
                viewModel.scanSpecificFolder(treeUri = uri, displayName = displayName)
            }
        },
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (startMonitorAfterPermission) {
                    startMonitorAfterPermission = false
                    requestStorageAndStartMonitor(context, viewModel)
                }
            }
        },
    )

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    if (showQuickScanOptions) {
        AlertDialog(
            onDismissRequest = { showQuickScanOptions = false },
            title = { Text("Quick Scan", fontWeight = FontWeight.Bold) },
            text = { Text("Pilih sumber yang ingin Anda pindai secara instan.") },
            confirmButton = {
                Button(
                    onClick = {
                        showQuickScanOptions = false
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                ) {
                    Text("Pilih File", color = Color.Black)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showQuickScanOptions = false
                        folderPickerLauncher.launch(null)
                    },
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentPurple)
                ) {
                    Text("Pilih Folder", color = AccentPurple)
                }
            },
            containerColor = DarkGreyCard,
            titleContentColor = Color.White,
            textContentColor = TextGrey
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            when (state.currentScreen) {
                ScannerScreenType.DASHBOARD -> DashboardTopBar()
                ScannerScreenType.HISTORY -> HistoryTopBar(onBack = { viewModel.navigateTo(ScannerScreenType.DASHBOARD) })
                ScannerScreenType.FULL_SCAN -> FullScanTopBar(
                    onBack = { viewModel.stopFullScan() },
                    startTime = state.fullScanStartTime
                )
                else -> {}
            }
        },
        bottomBar = {
            if (state.currentScreen != ScannerScreenType.FULL_SCAN) {
                DashboardBottomBar(
                    currentScreen = state.currentScreen,
                    onNavigate = { viewModel.navigateTo(it) }
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DashboardBackground
    ) { padding ->
        when (state.currentScreen) {
            ScannerScreenType.DASHBOARD -> {
                DashboardContent(
                    padding = padding,
                    state = state,
                    context = context,
                    viewModel = viewModel,
                    onQuickScanClick = { showQuickScanOptions = true },
                    onViewAllClick = { viewModel.navigateTo(ScannerScreenType.HISTORY) },
                    startMonitorAfterPermission = { startMonitorAfterPermission = it },
                    notificationPermissionLauncher = notificationPermissionLauncher
                )
            }
            ScannerScreenType.HISTORY -> {
                HistoryContent(
                    padding = padding,
                    state = state,
                    onFilterChange = { viewModel.setHistoryFilter(it) }
                )
            }
            ScannerScreenType.FULL_SCAN -> {
                FullScanContent(
                    padding = padding,
                    state = state,
                    onStopClick = { viewModel.stopFullScan() },
                    onStartClick = { 
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (!android.os.Environment.isExternalStorageManager()) {
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } else {
                                viewModel.performRealFullDeviceScan()
                            }
                        } else {
                            viewModel.performRealFullDeviceScan()
                        }
                    }
                )
            }
            ScannerScreenType.SETTINGS -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("Settings Screen", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun DashboardContent(
    padding: PaddingValues,
    state: ScannerUiState,
    context: Context,
    viewModel: ScannerViewModel,
    onQuickScanClick: () -> Unit,
    onViewAllClick: () -> Unit,
    startMonitorAfterPermission: (Boolean) -> Unit,
    notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        // Device Status Section
        item {
            DeviceStatusHeader()
            StatusCard(state.lastCheckedTime)
        }

        // Real-time Shield Section
        item {
            RealTimeShieldCard(
                isEnabled = state.isMonitorRunning,
                onToggle = { enabled ->
                    if (enabled) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            requestStorageAndStartMonitor(context, viewModel)
                        } else {
                            startMonitorAfterPermission(true)
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        viewModel.stopMonitor()
                    }
                }
            )
        }

        // Scanning Tools Section
        item {
            ScanningToolsSection(
                onQuickScan = onQuickScanClick,
                onFullScan = { viewModel.startFullDeviceScan() }
            )
        }

        // Recent Activity Section
        item {
            RecentActivitySection(results = state.datasetResults, onViewAll = onViewAllClick)
        }

        // Existing Scanning Progress
        if (state.isScanning) {
            item {
                ScanningProgressCard(state.progress)
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
fun HistoryContent(
    padding: PaddingValues,
    state: ScannerUiState,
    onFilterChange: (HistoryFilter) -> Unit
) {
    val foundResults = state.datasetResults.filter { it.predicted.finalLabel() == PredictionLabel.MALWARE }
    val cleanResults = state.datasetResults.filter { it.predicted.finalLabel() == PredictionLabel.SAFE }
    
    val currentList = if (state.historyFilter == HistoryFilter.FOUND) foundResults else cleanResults

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Scan History",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.historyFilter == HistoryFilter.FOUND,
                onClick = { onFilterChange(HistoryFilter.FOUND) },
                label = { Text("Threats (${foundResults.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ThreatRed.copy(alpha = 0.2f),
                    selectedLabelColor = ThreatRed,
                    labelColor = TextGrey
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = state.historyFilter == HistoryFilter.FOUND,
                    borderColor = Color.Transparent,
                    selectedBorderColor = ThreatRed.copy(alpha = 0.5f)
                )
            )
            FilterChip(
                selected = state.historyFilter == HistoryFilter.CLEAN,
                onClick = { onFilterChange(HistoryFilter.CLEAN) },
                label = { Text("Clean (${cleanResults.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.Green.copy(alpha = 0.1f),
                    selectedLabelColor = Color.Green,
                    labelColor = TextGrey
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = state.historyFilter == HistoryFilter.CLEAN,
                    borderColor = Color.Transparent,
                    selectedBorderColor = Color.Green.copy(alpha = 0.5f)
                )
            )
        }

        if (currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, tint = DarkGreyCard, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No activity recorded", color = TextGrey)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(currentList) { result ->
                    CompactHistoryItem(result)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
fun CompactHistoryItem(result: ScanItemResult) {
    val isMalware = result.predicted.finalLabel() == PredictionLabel.MALWARE
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkGreyCard.copy(alpha = 0.5f)),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isMalware) ThreatRed.copy(alpha = 0.1f) else AccentPurple.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMalware) Icons.Default.BugReport else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (isMalware) ThreatRed else AccentPurple,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.displayName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    text = "Recently scanned",
                    color = TextGrey,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isMalware) ThreatRed.copy(alpha = 0.1f) else Color.Green.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isMalware) "Threat" else "Clean",
                    color = if (isMalware) ThreatRed else Color.Green,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun DashboardTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "Menu",
            tint = Color.White
        )
        
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentPurple),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Logo",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        Box(modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "Profile",
                tint = Color.White,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color.Green, CircleShape)
                    .align(Alignment.BottomEnd)
                    .border(1.5.dp, Color.Black, CircleShape)
            )
        }
    }
}

@Composable
fun HistoryTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.clickable { onBack() }
        )
        
        Text(
            "SafeScan History",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Box(modifier = Modifier.size(24.dp))
    }
}

@Composable
fun FullScanTopBar(onBack: () -> Unit, startTime: Long?) {
    var ticks by remember { mutableLongStateOf(0L) }
    
    LaunchedEffect(startTime) {
        if (startTime != null) {
            while (true) {
                ticks = (System.currentTimeMillis() - startTime) / 1000
                kotlinx.coroutines.delay(1000)
            }
        } else {
            ticks = 0L
        }
    }

    val minutes = ticks / 60
    val seconds = ticks % 60
    val timeText = java.util.Locale.getDefault().let { locale ->
        String.format(locale, "%02d:%02d", minutes, seconds)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.clickable { onBack() }
        )
        
        Text(
            "Full Scan",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Surface(
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(timeText, color = AccentPurple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun FullScanContent(
    padding: PaddingValues,
    state: ScannerUiState,
    onStopClick: () -> Unit,
    onStartClick: () -> Unit
) {
    val progress = state.fullScanProgress
    val isScanning = state.isFullScanning
    val percent = if (progress != null && progress.total > 0) {
        (progress.completed.toFloat() / progress.total.toFloat() * 100).toInt()
    } else 0
    
    val threatsFound = state.fullScanResults.count { it.predicted.finalLabel() == PredictionLabel.MALWARE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (isScanning) "Analyzing System Files" else "Ready to Scan",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (isScanning) "Scanning root directory and installed packages..." else "Select a directory to begin a deep system audit.",
                color = TextGrey,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }

        // Circular Progress
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.05f), CircleShape)
            )
            
            CircularProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.size(220.dp),
                color = if (isScanning) AccentPurple else TextGrey.copy(alpha = 0.2f),
                strokeWidth = 12.dp,
                trackColor = Color.White.copy(alpha = 0.05f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = if (isScanning) AccentPurple else TextGrey,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$percent%",
                    color = if (isScanning) Color.White else TextGrey,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    if (isScanning) "DEEP SCANNING" else "STANDBY",
                    color = TextGrey,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // Current Analysis Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkGreyCard),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(if (isScanning) AccentPurple else TextGrey, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("CURRENT ANALYSIS", color = TextGrey, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = progress?.currentFileName ?: if (isScanning) "Initializing..." else "No active scan",
                    color = if (isScanning) AccentPurple else TextGrey,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Stats Boxes
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatBoxSimple(
                modifier = Modifier.weight(1f),
                label = "FILES SCANNED",
                value = String.format(java.util.Locale.getDefault(), "%,d", progress?.completed ?: 0),
                icon = Icons.Default.Description
            )
            StatBoxSimple(
                modifier = Modifier.weight(1f),
                label = "THREATS FOUND",
                value = String.format(java.util.Locale.getDefault(), "%02d", threatsFound),
                icon = Icons.Default.Warning,
                valueColor = if (threatsFound > 0) ThreatRed else Color.White
            )
        }

        // Overall Progress
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("OVERALL PROGRESS", color = TextGrey, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("$percent%", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = AccentPurple,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
        }

        // Action Button (Start / Stop)
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (isScanning) {
                Button(
                    onClick = onStopClick,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)), 
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.StopCircle, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Scan", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                Button(
                    onClick = onStartClick,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Full Scan", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            
            Text(
                if (isScanning) "STOP WILL CANCEL THE CURRENT SESSION. PROGRESS WILL NOT BE SAVED."
                else "A DEEP SCAN WILL ANALYZE ALL FILES FOR POTENTIAL SECURITY THREATS.",
                color = TextGrey,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun StatBoxSimple(modifier: Modifier, label: String, value: String, icon: ImageVector, valueColor: Color = Color.White) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkGreyCard)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = if (valueColor == ThreatRed) ThreatRed else TextGrey, modifier = Modifier.size(20.dp))
            Text(value, color = valueColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, color = TextGrey, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun DeviceStatusHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "DEVICE STATUS",
            color = TextGrey,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Simulate Alert",
            color = AccentPurple,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun StatusCard(lastChecked: Long?) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = StatusCardBlue)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier
                    .size(140.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 20.dp, y = (-20).dp),
                tint = Color.White.copy(alpha = 0.05f)
            )

            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SYSTEM SECURE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (lastChecked == null) "Not checked yet" 
                                   else "Checked ${formatRelativeTime(lastChecked)}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Text(
                    text = "Your Device is Protected",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "SafeScan real-time protection is monitoring your system.",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun RealTimeShieldCard(isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkGreyCard)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = Color(0xFF4FC3F7),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Real-Time Download Shield",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Monitoring active downloads",
                    color = TextGrey,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentPurple,
                    uncheckedThumbColor = TextGrey,
                    uncheckedTrackColor = Color.Black
                )
            )
        }
    }
}

@Composable
fun ScanningToolsSection(onQuickScan: () -> Unit, onFullScan: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(AccentPurple, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Scanning Tools",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ToolCard(
                modifier = Modifier.weight(1f),
                title = "Quick File Scan",
                description = "Scan specific files, APKs, or folders instantly.",
                buttonText = "Choose Item",
                icon = Icons.Default.CreateNewFolder,
                onButtonClick = onQuickScan,
                isPrimary = false,
            )
            ToolCard(
                modifier = Modifier.weight(1f),
                title = "Full Device Scan",
                description = "Comprehensive system-wide audit for security.",
                buttonText = "Start Full Scan",
                icon = Icons.Default.TrackChanges,
                onButtonClick = onFullScan,
                isPrimary = true
            )
        }
    }
}

@Composable
fun ToolCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    buttonText: String,
    icon: ImageVector,
    onButtonClick: () -> Unit,
    isPrimary: Boolean
) {
    Card(
        modifier = modifier.height(240.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = DarkGreyCard)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = description,
                    color = TextGrey,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }

            Button(
                onClick = onButtonClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPrimary) AccentPurple else Color.Black,
                    contentColor = if (isPrimary) Color.Black else Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Text(text = buttonText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RecentActivitySection(results: List<ScanItemResult>, onViewAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recent Activity",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "View All",
                color = AccentPurple,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onViewAll() }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No recent activity",
                        color = TextGrey,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                results.take(3).forEach { result ->
                    val isSafe = result.predicted.finalLabel() == PredictionLabel.SAFE
                    ActivityItem(
                        title = result.displayName,
                        subtitle = "Recently",
                        status = if (isSafe) "Clean" else "Threats",
                        isSecure = isSafe
                    )
                }
            }
        }
    }
}

@Composable
fun ActivityItem(title: String, subtitle: String, status: String, isSecure: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSecure) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isSecure) Color.White else ThreatRed,
                    modifier = Modifier.size(18.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = TextGrey,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSecure) Color.White.copy(alpha = 0.05f) else ThreatRed.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = status,
                    color = if (isSecure) Color.White else ThreatRed,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
fun DashboardBottomBar(currentScreen: ScannerScreenType, onNavigate: (ScannerScreenType) -> Unit) {
    Column {
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
        NavigationBar(
            containerColor = DashboardBackground,
            tonalElevation = 0.dp,
            windowInsets = NavigationBarDefaults.windowInsets
        ) {
            NavigationBarItem(
                selected = currentScreen == ScannerScreenType.DASHBOARD,
                onClick = { onNavigate(ScannerScreenType.DASHBOARD) },
                alwaysShowLabel = true,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (currentScreen == ScannerScreenType.DASHBOARD) AccentPurple.copy(alpha = 0.2f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (currentScreen == ScannerScreenType.DASHBOARD) Icons.Default.Home else Icons.Outlined.Home,
                            contentDescription = "Home",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                label = { Text("Home", fontSize = 11.sp, fontWeight = if (currentScreen == ScannerScreenType.DASHBOARD) FontWeight.Bold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentPurple,
                    selectedTextColor = AccentPurple,
                    unselectedIconColor = TextGrey,
                    unselectedTextColor = TextGrey,
                    indicatorColor = Color.Transparent
                )
            )
            NavigationBarItem(
                selected = currentScreen == ScannerScreenType.HISTORY,
                onClick = { onNavigate(ScannerScreenType.HISTORY) },
                alwaysShowLabel = true,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (currentScreen == ScannerScreenType.HISTORY) AccentPurple.copy(alpha = 0.2f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (currentScreen == ScannerScreenType.HISTORY) Icons.Default.History else Icons.Outlined.History,
                            contentDescription = "History",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                label = { Text("History", fontSize = 11.sp, fontWeight = if (currentScreen == ScannerScreenType.HISTORY) FontWeight.Bold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentPurple,
                    selectedTextColor = AccentPurple,
                    unselectedIconColor = TextGrey,
                    unselectedTextColor = TextGrey,
                    indicatorColor = Color.Transparent
                )
            )
            NavigationBarItem(
                selected = currentScreen == ScannerScreenType.SETTINGS,
                onClick = { onNavigate(ScannerScreenType.SETTINGS) },
                alwaysShowLabel = true,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (currentScreen == ScannerScreenType.SETTINGS) AccentPurple.copy(alpha = 0.2f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (currentScreen == ScannerScreenType.SETTINGS) Icons.Default.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                label = { Text("Settings", fontSize = 11.sp, fontWeight = if (currentScreen == ScannerScreenType.SETTINGS) FontWeight.Bold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentPurple,
                    selectedTextColor = AccentPurple,
                    unselectedIconColor = TextGrey,
                    unselectedTextColor = TextGrey,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun ScanningProgressCard(progress: ScanProgress?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkGreyCard)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scanning in progress...", color = Color.White, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = {
                    if (progress != null && progress.total > 0) {
                        progress.completed.toFloat() / progress.total.toFloat()
                    } else 0f
                },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = AccentPurple,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
            progress?.let {
                Text(
                    text = "${it.completed}/${it.total} files - ${it.currentFileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGrey
                )
            }
        }
    }
}

private fun formatRelativeTime(timeMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timeMillis
    return when {
        diff < 60000 -> "just now"
        else -> DateUtils.getRelativeTimeSpanString(timeMillis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}

private fun requestStorageAndStartMonitor(context: Context, viewModel: ScannerViewModel) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!android.os.Environment.isExternalStorageManager()) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            return
        }
    }
    viewModel.startMonitor()
}

private fun persistReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    if (uri.toString().contains("/tree/")) {
        val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
        docFile?.name?.let { return it }
    }

    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
}
