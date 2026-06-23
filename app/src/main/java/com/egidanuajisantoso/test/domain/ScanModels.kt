package com.egidanuajisantoso.test.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.util.Locale

enum class PredictionLabel {
    SAFE,
    MALWARE;

    fun displayName(): String = when (this) {
        SAFE -> "Safe"
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

/**
 * Threshold for determining malware.
 * 0.8f (80%) to reduce false positives.
 */
const val MALWARE_DECISION_THRESHOLD = 0.8f

fun ClassificationScore.finalLabel(): PredictionLabel =
    if (malwareProbability >= MALWARE_DECISION_THRESHOLD) PredictionLabel.MALWARE else PredictionLabel.SAFE

data class ScanItemResult(
    val displayName: String,
    val uri: Uri,
    val predicted: ClassificationScore,
    val sourceHint: String? = null,
)

data class ScanProgress(
    val completed: Int,
    val total: Int,
    val currentFileName: String,
)

data class DatasetSummary(
    val totalFiles: Int,
    val safeFiles: Int,
    val malwareFiles: Int,
    val labeledFiles: Int = 0,
    val correctlyClassified: Int = 0,
)

data class BinaryImageTensor(
    val inputShape: LongArray,
    val values: FloatArray,
)

object BinaryImagePreprocessor {
    const val DEFAULT_WIDTH = 224
    const val DEFAULT_HEIGHT = 224
    private const val CHANNELS = 3

    @Volatile
    var useCenteredNormalization: Boolean = false 

    @Volatile
    var useBgr: Boolean = false 

    @Volatile
    var useImageNetNormalization: Boolean = true 

    @Volatile
    var useBitmapDecodeForImages: Boolean = true
    
    @Volatile
    var outputIndexMalware: Int = 1 
    
    fun toTensor(
        bytes: ByteArray,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        fileName: String? = null,
    ): BinaryImageTensor {
        // Hanya gunakan Bitmap Decode jika ekstensi AKHIRNYA adalah gambar asli
        val isTrueImage = fileName?.lowercase()?.let { 
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png")
        } ?: false

        if (useBitmapDecodeForImages && isTrueImage) {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            if (decoded != null) {
                val resized = if (decoded.width == width && decoded.height == height) decoded
                else Bitmap.createScaledBitmap(decoded, width, height, true)

                val pixelCount = width * height
                val pixels = IntArray(pixelCount)
                resized.getPixels(pixels, 0, width, 0, 0, width, height)

                val chw = FloatArray(CHANNELS * pixelCount)
                for (index in 0 until pixelCount) {
                    val pixel = pixels[index]
                    val redRaw = ((pixel shr 16) and 0xFF) / 255f
                    val greenRaw = ((pixel shr 8) and 0xFF) / 255f
                    val blueRaw = (pixel and 0xFF) / 255f

                    val (red, green, blue) = if (useImageNetNormalization) {
                        val r = (redRaw - 0.485f) / 0.229f
                        val g = (greenRaw - 0.456f) / 0.224f
                        val b = (blueRaw - 0.406f) / 0.225f
                        Triple(r, g, b)
                    } else {
                        val r = if (useCenteredNormalization) redRaw * 2f - 1f else redRaw
                        val g = if (useCenteredNormalization) greenRaw * 2f - 1f else greenRaw
                        val b = if (useCenteredNormalization) blueRaw * 2f - 1f else blueRaw
                        Triple(r, g, b)
                    }

                    if (useBgr) {
                        chw[index] = blue
                        chw[pixelCount + index] = green
                        chw[pixelCount * 2 + index] = red
                    } else {
                        chw[index] = red
                        chw[pixelCount + index] = green
                        chw[pixelCount * 2 + index] = blue
                    }
                }

                return BinaryImageTensor(
                    inputShape = longArrayOf(1L, CHANNELS.toLong(), height.toLong(), width.toLong()),
                    values = chw,
                )
            }
        }

        val pixelCount = width * height
        val grayscale = FloatArray(pixelCount)

        if (bytes.isEmpty()) {
            grayscale.fill(0f)
        } else if (bytes.size >= pixelCount) {
            val step = bytes.size.toDouble() / pixelCount.toDouble()
            for (index in 0 until pixelCount) {
                val sourceIndex = (index * step).toInt().coerceIn(0, bytes.lastIndex)
                grayscale[index] = (bytes[sourceIndex].toInt() and 0xFF) / 255f
            }
        } else {
            for (index in 0 until pixelCount) {
                grayscale[index] = if (index < bytes.size) {
                    (bytes[index].toInt() and 0xFF) / 255f
                } else {
                    0f
                }
            }
        }

        val chw = FloatArray(CHANNELS * pixelCount)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)  
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)   

        val channelOrder = if (useBgr) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)

        for (channel in 0 until CHANNELS) {
            val outChannel = channelOrder[channel]
            val offset = outChannel * pixelCount
            for (i in 0 until pixelCount) {
                var v = grayscale[i]
                v = (v - mean[channel]) / std[channel]
                chw[offset + i] = v
            }
        }

        return BinaryImageTensor(
            inputShape = longArrayOf(1L, CHANNELS.toLong(), height.toLong(), width.toLong()),
            values = chw,
        )
    }
}

fun isSupportedImageName(fileName: String?): Boolean {
    if (fileName.isNullOrBlank()) return false
    val normalized = fileName.lowercase(Locale.getDefault())
    return normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") || normalized.endsWith(".png")
}
