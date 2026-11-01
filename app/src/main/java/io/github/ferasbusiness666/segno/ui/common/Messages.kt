package io.github.ferasbusiness666.segno.ui.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Short messages for the bar at the bottom ("Added to the queue"). */
object Messages {
    private val _flow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val flow: SharedFlow<String> = _flow

    fun show(text: String) {
        _flow.tryEmit(text)
    }
}
