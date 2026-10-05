package com.okb.whatsappbridge.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.usecase.RecycleBinUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecycleBinViewModel(
    repository: MessageRepository,
    mediaRepository: MediaRepository,
    private val recycleBin: RecycleBinUseCase,
) : ViewModel() {

    val retentionDays: Long = recycleBin.retentionMillis / (24 * 60 * 60 * 1000)

    val messages: StateFlow<List<BridgeMessage>?> =
        combine(repository.observeRecycleBin(LIMIT), mediaRepository.observeMessageMediaSummaries()) { msgs, media ->
            msgs.map { it.copy(media = media[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    fun toggleSelection(id: String) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selected.value = messages.value.orEmpty().map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun restoreSelected() = act { recycleBin.restore(it).let { n -> "Restored $n message(s)" } }

    fun deleteSelectedForever() = act { recycleBin.deleteForever(it).let { n -> "Deleted $n message(s) forever" } }

    fun emptyBin() {
        _selected.value = emptySet()
        viewModelScope.launch { _events.emit("Recycle Bin emptied: ${recycleBin.emptyBin()} message(s) deleted forever") }
    }

    private fun act(block: suspend (Set<String>) -> String) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        _selected.value = emptySet()
        viewModelScope.launch { _events.emit(block(ids)) }
    }

    companion object {
        const val LIMIT = 500
    }
}
