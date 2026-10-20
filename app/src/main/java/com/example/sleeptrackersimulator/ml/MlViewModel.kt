package com.example.sleeptrackersimulator.ml

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.SensorSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class MlViewModel(
    private val samples: Flow<SensorSample>,
    private val classifier: ActivityClassifier,
) : ViewModel() {
    private val buffer = SensorWindowBuffer(20)
    private val droppedWindowsCounter = AtomicLong(0)
    private val mutableDroppedWindowCount = MutableStateFlow(0L)
    val droppedWindowCount: StateFlow<Long> = mutableDroppedWindowCount.asStateFlow()
    private val windows = Channel<SensorWindowBuffer.CompleteWindow>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = {
            mutableDroppedWindowCount.value = droppedWindowsCounter.incrementAndGet()
        },
    )
    private val mutableState = MutableStateFlow<MlUiState>(MlUiState.Collecting)
    val state: StateFlow<MlUiState> = mutableState.asStateFlow()
    private val mutablePredictions = MutableSharedFlow<ActivityPrediction>(extraBufferCapacity = 8)
    val predictions: SharedFlow<ActivityPrediction> = mutablePredictions.asSharedFlow()

    init {
        viewModelScope.launch {
            samples.collect { sample ->
                buffer.add(sample)?.let { windows.trySend(it) }
            }
        }
        viewModelScope.launch {
            for (window in windows) {
                mutableState.value = try {
                    val prediction = withContext(Dispatchers.Default) {
                        classifier.classify(window.values, window.endTimestampNanos)
                    }
                    mutablePredictions.emit(prediction)
                    MlUiState.Ready(prediction)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    MlUiState.Error(error.message ?: "TFLite inference failed")
                }
            }
        }
    }

    override fun onCleared() {
        windows.close()
        buffer.clear()
        classifier.close()
        super.onCleared()
    }

    companion object {
        fun factory(samples: Flow<SensorSample>, classifier: ActivityClassifier) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MlViewModel(samples, classifier) as T
            }
    }
}
