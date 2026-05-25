package com.egidanuajisantoso.test.domain

import android.net.Uri
import java.util.Locale

enum class PredictionLabel {
    SAFE,
    MALWARE;

    fun displayName(): String = when (this) {
        SAFE -> "Aman"
        MALWARE -> "Malware"
    }
}

data class ClassificationScore(
    val label: PredictionLabel,
    val safeProbability: Float,
    val malwareProbability: Float,
    val confidence: Float,
    val rawScores: FloatArray,
)

data class ScanItemResult(
    val displayName: String,
    val uri: Uri,
    val predicted: ClassificationScore,
    val expectedLabel: PredictionLabel? = null,
    val isCorrect: Boolean? = null,
    val sourceHint: String? = null,
)

data class ScanProgress(
    val completed: Int,
    val total: Int,
    val currentFileName: String,
) {
    val progressFraction: Float = if (total <= 0) 0f else completed.toFloat() / total.toFloat()
}

data class DatasetSummary(
    val totalFiles: Int,
    val safeFiles: Int,
    val malwareFiles: Int,
    val labeledFiles: Int,
    val correctlyClassified: Int,
) {
    val accuracyPercent: Float?
        get() = if (labeledFiles == 0) null else correctlyClassified.toFloat() / labeledFiles.toFloat() * 100f
}

data class BinaryImageTensor(
    val inputShape: LongArray,
    val values: FloatArray,
)

object BinaryImagePreprocessor {
    const val DEFAULT_WIDTH = 224
    const val DEFAULT_HEIGHT = 224
    private const val CHANNELS = 3

    fun toTensor(bytes: ByteArray, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT): BinaryImageTensor {
        val pixelCount = width * height
        val grayscale = FloatArray(pixelCount)

        when {
            bytes.isEmpty() -> grayscale.fill(0f)
            bytes.size >= pixelCount -> {
                for (index in 0 until pixelCount) {
                    val sourceIndex = ((index.toLong() * bytes.size) / pixelCount).toInt().coerceIn(0, bytes.lastIndex)
                    grayscale[index] = (bytes[sourceIndex].toInt() and 0xFF) / 255f
                }
            }
            else -> {
                for (index in 0 until pixelCount) {
                    grayscale[index] = if (index < bytes.size) {
                        (bytes[index].toInt() and 0xFF) / 255f
                    } else {
                        0f
                    }
                }
            }
        }

        val chw = FloatArray(CHANNELS * pixelCount)
        for (channel in 0 until CHANNELS) {
            val offset = channel * pixelCount
            for (index in 0 until pixelCount) {
                chw[offset + index] = grayscale[index]
            }
        }

        return BinaryImageTensor(
            inputShape = longArrayOf(1L, CHANNELS.toLong(), height.toLong(), width.toLong()),
            values = chw,
        )
    }
}

fun inferExpectedLabel(pathHint: String?): PredictionLabel? {
    if (pathHint.isNullOrBlank()) return null
    val text = pathHint.lowercase(Locale.getDefault())
    return when {
        listOf("malware", "virus", "infected", "danger").any { it in text } -> PredictionLabel.MALWARE
        listOf("safe", "benign", "clean", "normal", "good").any { it in text } -> PredictionLabel.SAFE
        else -> null
    }
}

