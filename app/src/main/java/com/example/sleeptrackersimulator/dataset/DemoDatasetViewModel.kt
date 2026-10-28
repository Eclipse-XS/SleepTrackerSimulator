package com.example.sleeptrackersimulator.dataset

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.data.sessions.RoomSessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DemoDatasetUiState(
    val generatedCount: Int = 0,
    val importedCount: Int = 0,
    val isBusy: Boolean = false,
    val message: String = "No demo dataset generated",
    val exportPath: String? = null,
)

class DemoDatasetViewModel(
    private val context: Context,
    private val repository: RoomSessionRepository,
    private val generator: DemoDatasetGenerator = DemoDatasetGenerator(),
) : ViewModel() {
    private var generated: List<DemoNight> = emptyList()
    private val _state = MutableStateFlow(DemoDatasetUiState())
    val state: StateFlow<DemoDatasetUiState> = _state

    fun generate() {
        generated = generator.generate()
        val errors = generator.validate(generated)
        _state.value = DemoDatasetUiState(
            generatedCount = generated.size,
            importedCount = _state.value.importedCount,
            message = if (errors.isEmpty()) "Generated 90 deterministic synthetic nights" else errors.joinToString(),
        )
    }

    fun importHistory() = runBusy {
        if (generated.isEmpty()) generated = generator.generate()
        val errors = generator.validate(generated)
        require(errors.isEmpty()) { errors.joinToString() }
        repository.importDemoNights(generated)
        val count = repository.countDemoNights()
        _state.update { it.copy(importedCount = count, message = "Imported $count demo nights; repeated import is idempotent") }
    }

    fun verify() = runBusy {
        val count = repository.countDemoNights()
        val errors = repository.verifyDemoNights()
        _state.update {
            it.copy(
                importedCount = count,
                message = if (errors.isEmpty()) "Room integrity verified: 90 synthetic sessions and summaries" else "Integrity failed: ${errors.joinToString(limit = 3)}",
            )
        }
    }

    fun reset() = runBusy {
        val deleted = repository.resetDemoNights()
        _state.update { it.copy(importedCount = repository.countDemoNights(), message = "Deleted $deleted demo sessions; real sessions were not targeted") }
    }

    fun exportCsv() = runBusy {
        if (generated.isEmpty()) generated = generator.generate()
        val directory = context.filesDir.resolve("exports").apply { mkdirs() }
        val file = directory.resolve("sleepsense-demo-90-v1.csv")
        file.writeText(generator.toCsv(generated))
        _state.update { it.copy(exportPath = file.absolutePath, message = "CSV exported: ${file.absolutePath}") }
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            runCatching { block() }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: error.javaClass.simpleName) } }
            _state.update { it.copy(isBusy = false) }
        }
    }

    companion object {
        fun factory(context: Context, repository: RoomSessionRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DemoDatasetViewModel(context.applicationContext, repository) as T
        }
    }
}
