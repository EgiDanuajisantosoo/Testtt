package com.egidanuajisantoso.test.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

const val MALWARE_DECISION_THRESHOLD = 0.5f

fun ClassificationScore.finalLabel(): PredictionLabel =
    if (malwareProbability >= MALWARE_DECISION_THRESHOLD) PredictionLabel.MALWARE else PredictionLabel.SAFE

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
)

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

    // Toggles used by UI for quick tuning.
    @Volatile
    var useCenteredNormalization: Boolean = false // false => 0..1, true => -1..1

    @Volatile
    var useBgr: Boolean = true // REQUIRED for model accuracy with certain malware-to-image conversions

    /**
     * CRITICAL: ImageNet normalization is REQUIRED for malware_model_binary.onnx.
     * Model was trained with mean=[0.485, 0.456, 0.406] and std=[0.229, 0.224, 0.225].
     * This is NOT optional - it must always be applied before inference.
     */
    @Volatile
    var useImageNetNormalization: Boolean = true // ALWAYS true for this model

    // If true, decode image files as RGB bitmaps before tensor conversion.
    @Volatile
    var useBitmapDecodeForImages: Boolean = true
    
    /**
     * Output index mapping based on model architecture.
     * For malware_model_binary.onnx:
     * Index 0 = benign (SAFE)
     * Index 1 = malware (DANGEROUS)
     * Therefore, outputIndexMalware must be 1 to correctly read malware probability
     */
    @Volatile
    var outputIndexMalware: Int = 1 // FIXED: Must be 1 for this model
    
    fun toggleOutputIndex() {
        outputIndexMalware = if (outputIndexMalware == 0) 1 else 0
    }

    /**
     * Convert raw file bytes into a tensor expected by the ONNX model.
     * Pipeline (as in README/flowchart):
     *  - read bytes
     *  - convert to 2D grayscale matrix by sampling/padding
     *  - (implicit) resize to target resolution by sampling logic below
     *  - replicate into RGB channels (or BGR if toggled)
     *  - normalize according to flags
     */
    fun toTensor(
        bytes: ByteArray,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        fileName: String? = null,
    ): BinaryImageTensor {
        if (useBitmapDecodeForImages && isSupportedImageName(fileName)) {
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

        // Build grayscale values by sampling the byte array into [0..255], then scale to 0..1
        val grayscale = FloatArray(pixelCount)

        if (bytes.isEmpty()) {
            grayscale.fill(0f)
        } else if (bytes.size >= pixelCount) {
            // When there are more bytes than needed, sample uniformly to fill the image
            for (index in 0 until pixelCount) {
                val sourceIndex = ((index.toLong() * bytes.size) / pixelCount).toInt().coerceIn(0, bytes.lastIndex)
                grayscale[index] = (bytes[sourceIndex].toInt() and 0xFF) / 255f
            }
        } else {
            // When bytes are fewer, copy then pad with zeros
            for (index in 0 until pixelCount) {
                grayscale[index] = if (index < bytes.size) {
                    (bytes[index].toInt() and 0xFF) / 255f
                } else {
                    0f
                }
            }
        }

        // Prepare final CHW float buffer
        val chw = FloatArray(CHANNELS * pixelCount)

        // CRITICAL: ImageNet normalization MUST ALWAYS be applied for malware_model_binary.onnx
        // Model was trained with these specific mean/std values per channel
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)  // REQUIRED for model accuracy
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)   // REQUIRED for model accuracy

        // Determine channel order indices
        val channelOrder = if (useBgr) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)

        for (channel in 0 until CHANNELS) {
            val outChannel = channelOrder[channel]
            val offset = outChannel * pixelCount
            for (i in 0 until pixelCount) {
                var v = grayscale[i]
                // ALWAYS apply ImageNet normalization (subtract mean and divide by std)
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

fun inferExpectedLabel(pathHint: String?): PredictionLabel? {
    if (pathHint.isNullOrBlank()) return null
    val text = pathHint.lowercase(Locale.getDefault())
    return when {
        listOf("malware", "virus", "infected", "danger").any { it in text } -> PredictionLabel.MALWARE
        listOf("safe", "benign", "clean", "normal", "good").any { it in text } -> PredictionLabel.SAFE
        else -> null
    }
}


