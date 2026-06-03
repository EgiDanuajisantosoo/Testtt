package com.egidanuajisantoso.test.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ScanResultBus {
    private val _events = MutableSharedFlow<ScanItemResult>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun emitResult(result: ScanItemResult) {
        _events.tryEmit(result)
    }
}
