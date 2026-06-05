package com.egidanuajisantoso.test.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.egidanuajisantoso.test.domain.DatasetSummary
import com.egidanuajisantoso.test.domain.OnnxMalwareClassifier
import com.egidanuajisantoso.test.domain.PredictionLabel
import com.egidanuajisantoso.test.domain.ScanItemResult
import com.egidanuajisantoso.test.domain.ScanProgress
import com.egidanuajisantoso.test.domain.inferExpectedLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ScannerRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val classifier = OnnxMalwareClassifier(appContext)

    suspend fun scanSingleFile(
        uri: Uri,
        displayName: String,
        pathHint: String? = displayName,
    ): ScanItemResult = withContext(Dispatchers.IO) {
        val bytes = readBytes(uri)
        val score = classifier.classify(bytes, displayName)
        val expectedLabel = inferExpectedLabel(pathHint)
        ScanItemResult(
            displayName = displayName,
            uri = uri,
            predicted = score,
            expectedLabel = expectedLabel,
            isCorrect = expectedLabel?.let { it == score.label },
            sourceHint = pathHint,
        )
    }

    suspend fun scanTree(
        treeUri: Uri,
        onProgress: (ScanProgress) -> Unit,
        onItemResult: (ScanItemResult) -> Unit,
    ): DatasetSummary = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: error("Folder tidak dapat dibuka dari URI: $treeUri")

        val files = mutableListOf<FileEntry>()
        collectFiles(root, root.name ?: "dataset", files)
        val total = files.size

        var safeFiles = 0
        var malwareFiles = 0
        var labeledFiles = 0
        var correctFiles = 0

        files.forEachIndexed { index, entry ->
            onProgress(
                ScanProgress(
                    completed = index,
                    total = total,
                    currentFileName = entry.displayName,
                )
            )

            val score = classifier.classify(readBytes(entry.document.uri), entry.displayName)
            val expectedLabel = inferExpectedLabel(entry.pathHint)
            val result = ScanItemResult(
                displayName = entry.displayName,
                uri = entry.document.uri,
                predicted = score,
                expectedLabel = expectedLabel,
                isCorrect = expectedLabel?.let { it == score.label },
                sourceHint = entry.pathHint,
            )

            when (score.label) {
                PredictionLabel.SAFE -> safeFiles++
                PredictionLabel.MALWARE -> malwareFiles++
            }

            if (result.expectedLabel != null) {
                labeledFiles++
                if (result.isCorrect == true) {
                    correctFiles++
                }
            }

            onItemResult(result)
            onProgress(
                ScanProgress(
                    completed = index + 1,
                    total = total,
                    currentFileName = entry.displayName,
                )
            )
        }

        DatasetSummary(
            totalFiles = total,
            safeFiles = safeFiles,
            malwareFiles = malwareFiles,
            labeledFiles = labeledFiles,
            correctlyClassified = correctFiles,
        )
    }

    suspend fun scanFullFileSystem(
        rootFile: java.io.File,
        onProgress: (ScanProgress) -> Unit,
        onItemResult: (ScanItemResult) -> Unit,
    ): DatasetSummary = withContext(Dispatchers.IO) {
        // Step 1: Count files quickly to provide a total for the progress bar
        var totalFilesCount = 0
        fun countFiles(dir: java.io.File) {
            val list = dir.listFiles() ?: return
            for (file in list) {
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) countFiles(file)
                } else {
                    totalFilesCount++
                }
            }
        }
        
        onProgress(ScanProgress(0, 0, "Menghitung total file..."))
        countFiles(rootFile)
        
        if (totalFilesCount == 0) {
            // Check if it's a permission issue or actually empty
            val testList = rootFile.listFiles()
            if (testList == null) {
                error("Izin ditolak atau folder tidak dapat diakses: ${rootFile.absolutePath}")
            }
            return@withContext DatasetSummary(0, 0, 0, 0, 0)
        }

        // Step 2: Scan files while walking the tree again
        var safeFiles = 0
        var malwareFiles = 0
        var currentFileIndex = 0

        fun scanRecursive(dir: java.io.File) {
            val list = dir.listFiles() ?: return
            for (file in list) {
                ensureActive()
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) scanRecursive(file)
                } else {
                    currentFileIndex++
                    onProgress(ScanProgress(currentFileIndex, totalFilesCount, file.name))
                    
                    val bytes = runCatching { file.readBytes() }.getOrElse { ByteArray(0) }
                    if (bytes.isNotEmpty()) {
                        val score = classifier.classify(bytes, file.name)
                        val result = ScanItemResult(
                            displayName = file.name,
                            uri = Uri.fromFile(file),
                            predicted = score,
                            sourceHint = file.absolutePath
                        )

                        if (score.label == PredictionLabel.SAFE) safeFiles++ else malwareFiles++
                        onItemResult(result)
                    }
                }
            }
        }

        scanRecursive(rootFile)

        DatasetSummary(totalFilesCount, safeFiles, malwareFiles, 0, 0)
    }

    private fun collectFilesPhysical(dir: java.io.File, output: MutableList<java.io.File>) {
        val list = dir.listFiles() ?: return
        for (file in list) {
            if (file.isDirectory) {
                // Skip some system/hidden folders to avoid infinite loops or permission issues
                if (!file.name.startsWith(".")) {
                    collectFilesPhysical(file, output)
                }
            } else {
                output.add(file)
            }
        }
    }

    private fun collectFiles(
        documentFile: DocumentFile,
        pathHint: String,
        output: MutableList<FileEntry>,
    ) {
        if (documentFile.isFile) {
            output += FileEntry(
                document = documentFile,
                displayName = documentFile.name ?: documentFile.uri.lastPathSegment ?: "unknown",
                pathHint = pathHint,
            )
            return
        }

        if (!documentFile.isDirectory) return

        documentFile.listFiles().forEach { child ->
            val childName = child.name ?: child.uri.lastPathSegment ?: "unknown"
            val childPathHint = "$pathHint/$childName"
            collectFiles(child, childPathHint, output)
        }
    }

    private fun readBytes(uri: Uri): ByteArray {
        appContext.contentResolver.openInputStream(uri).use { input ->
            if (input == null) {
                error("Tidak dapat membaca file: $uri")
            }
            return input.readBytes()
        }
    }

    private data class FileEntry(
        val document: DocumentFile,
        val displayName: String,
        val pathHint: String,
    )
}
