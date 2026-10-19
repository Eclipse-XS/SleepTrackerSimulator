package com.example.sleeptrackersimulator.ml

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.ui.dashboard.SensorUiState
import com.example.sleeptrackersimulator.core.model.SensorSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MlViewModel(
    private val samples: Flow<SensorSample>,
    private val classifier: ActivityClassifier,
) : ViewModel() {
    private val buffer = SensorWindowBuffer(20)
    private val mutableState = MutableStateFlow<MlUiState>(MlUiState.Collecting)
    val state: StateFlow<MlUiState> = mutableState

    init {
        viewModelScope.launch {
            samples.collectLatest { sample ->
                val window = buffer.add(sample) ?: return@collectLatest
                mutableState.value = try {
                    MlUiState.Ready(withContext(Dispatchers.Default) { classifier.classify(window) })
                } catch (error: Exception) {
                    MlUiState.Error(error.message ?: "TFLite inference failed")
                }
            }
        }
    }

    override fun onCleared() {
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
