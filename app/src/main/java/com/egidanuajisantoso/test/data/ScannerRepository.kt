package com.egidanuajisantoso.test.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.egidanuajisantoso.test.domain.ClassificationScore
import com.egidanuajisantoso.test.domain.DatasetSummary
import com.egidanuajisantoso.test.domain.OnnxMalwareClassifier
import com.egidanuajisantoso.test.domain.PredictionLabel
import com.egidanuajisantoso.test.domain.ScanItemResult
import com.egidanuajisantoso.test.domain.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

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
        ScanItemResult(
            displayName = displayName,
            uri = uri,
            predicted = score,
            sourceHint = pathHint,
        )
    }

    suspend fun scanTree(
        treeUri: Uri,
        onProgress: (ScanProgress) -> Unit,
        onItemResult: (ScanItemResult) -> Unit,
    ): DatasetSummary = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: error("Cannot open folder from URI: $treeUri")

        val files = mutableListOf<FileEntry>()
        collectFiles(root, root.name ?: "folder", files)
        val total = files.size

        var safeFiles = 0
        var malwareFiles = 0

        files.forEachIndexed { index, entry ->
            onProgress(
                ScanProgress(
                    completed = index,
                    total = total,
                    currentFileName = entry.displayName,
                )
            )

            val bytes = runCatching { readBytes(entry.document.uri) }.getOrElse { ByteArray(0) }
            val score = classifier.classify(bytes, entry.displayName)
            
            val result = ScanItemResult(
                displayName = entry.displayName,
                uri = entry.document.uri,
                predicted = score,
                sourceHint = entry.pathHint,
            )

            if (score.label == PredictionLabel.SAFE) safeFiles++ else malwareFiles++

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
            labeledFiles = 0,
            correctlyClassified = 0,
        )
    }

    suspend fun scanFullFileSystem(
        rootFile: File,
        isTrainingMode: Boolean = false, // Kept for signature compatibility but ignored
        onProgress: (ScanProgress) -> Unit,
        onItemResult: (ScanItemResult) -> Unit,
    ): DatasetSummary = withContext(Dispatchers.IO) {
        var totalFilesCount = 0
        fun countFiles(dir: File) {
            val list = dir.listFiles() ?: return
            for (file in list) {
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) countFiles(file)
                } else {
                    totalFilesCount++
                }
            }
        }
        
        onProgress(ScanProgress(0, 0, "Counting files..."))
        countFiles(rootFile)
        
        if (totalFilesCount == 0) return@withContext DatasetSummary(0, 0, 0, 0, 0)

        var safeFiles = 0
        var malwareFiles = 0
        var currentFileIndex = 0

        fun scanRecursive(dir: File) {
            val list = dir.listFiles() ?: return
            for (file in list) {
                ensureActive()
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) scanRecursive(file)
                } else {
                    currentFileIndex++
                    val displayIndex = if (currentFileIndex > totalFilesCount) totalFilesCount else currentFileIndex
                    onProgress(ScanProgress(displayIndex, totalFilesCount, file.name))
                    
                    val bytes = runCatching { file.readBytes() }.getOrElse { ByteArray(0) }
                    if (bytes.isNotEmpty()) {
                        val fileName = file.name.lowercase()
                        
                        val score = if (isMediaExtension(fileName)) {
                            if (isHeaderValid(fileName, bytes)) {
                                ClassificationScore(
                                    label = PredictionLabel.SAFE,
                                    safeProbability = 1.0f,
                                    malwareProbability = 0.0f,
                                    confidence = 1.0f,
                                    rawScores = floatArrayOf(10f, -10f)
                                )
                            } else {
                                classifier.classify(bytes, file.name)
                            }
                        } else {
                            classifier.classify(bytes, file.name)
                        }

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

    private fun isMediaExtension(name: String): Boolean {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || 
               name.endsWith(".png") || name.endsWith(".mp4") || 
               name.endsWith(".mp3") || name.endsWith(".opus") || 
               name.endsWith(".webp") || name.endsWith(".gif")
    }

    private fun isHeaderValid(name: String, bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> {
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            }
            name.endsWith(".png") -> {
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()
            }
            name.endsWith(".mp3") -> {
                (bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) || 
                (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0)
            }
            else -> true 
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
            if (input == null) error("Cannot read file: $uri")
            return input.readBytes()
        }
    }

    private data class FileEntry(
        val document: DocumentFile,
        val displayName: String,
        val pathHint: String,
    )
}
