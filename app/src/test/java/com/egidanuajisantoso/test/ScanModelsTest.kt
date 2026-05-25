package com.egidanuajisantoso.test

import com.egidanuajisantoso.test.domain.BinaryImagePreprocessor
import com.egidanuajisantoso.test.domain.PredictionLabel
import com.egidanuajisantoso.test.domain.inferExpectedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScanModelsTest {
    @Test
    fun `infer expected label from malware path`() {
        assertEquals(PredictionLabel.MALWARE, inferExpectedLabel("dataset/malware/sample.bin"))
    }

    @Test
    fun `infer expected label from clean path`() {
        assertEquals(PredictionLabel.SAFE, inferExpectedLabel("dataset/benign/sample.bin"))
    }

    @Test
    fun `preprocessor produces 1x3x224x224 tensor`() {
        val tensor = BinaryImagePreprocessor.toTensor(byteArrayOf(1, 2, 3, 4))
        assertEquals(longArrayOf(1L, 3L, 224L, 224L).toList(), tensor.inputShape.toList())
        assertEquals(3 * 224 * 224, tensor.values.size)
        assertNotNull(tensor.values.firstOrNull())
    }

    @Test
    fun `unknown label returns null`() {
        assertNull(inferExpectedLabel("dataset/misc/sample.bin"))
    }
}

