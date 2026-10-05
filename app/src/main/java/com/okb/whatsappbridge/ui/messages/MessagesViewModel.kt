package com.okb.whatsappbridge.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.usecase.RecycleBinUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A message for the snackbar; [undoIds] non-empty offers "Undo" (restore from the Recycle Bin). */
data class MessagesEvent(val text: String, val undoIds: Set<String> = emptySet())

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModel(
    private val repository: MessageRepository,
    mediaRepository: MediaRepository,
    private val recycleBin: RecycleBinUseCase,
) : ViewModel() {
    private val _filter = MutableStateFlow<UploadStatus?>(null)
    val filter: StateFlow<UploadStatus?> = _filter.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    /** Ids selected for a bulk action (long-press a message to start selecting). */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _events = MutableSharedFlow<MessagesEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MessagesEvent> = _events

    private val filtered = _filter.flatMapLatest { repository.observeRecent(it, LIMIT) }

    /** Messages joined with their media summary so the list can show per-message media state. */
    val messages: StateFlow<List<BridgeMessage>?> =
        combine(filtered, mediaRepository.observeMessageMediaSummaries()) { msgs, media ->
            msgs.map { it.copy(media = media[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recycleBinCount: StateFlow<Int> =
        repository.observeRecycleBinCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setFilter(status: UploadStatus?) {
        _filter.value = status
        _selected.value = emptySet()
    }

    fun toggleSelection(id: String) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selected.value = messages.value.orEmpty().map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Moves the selected messages to the Recycle Bin (with "Undo" in the snackbar). */
    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        _selected.value = emptySet()
        viewModelScope.launch {
            val moved = recycleBin.moveToBin(ids)
            _events.emit(MessagesEvent("Moved $moved message(s) to the Recycle Bin", undoIds = ids))
        }
    }

    fun undoDelete(ids: Set<String>) {
        viewModelScope.launch {
            val restored = recycleBin.restore(ids)
            _events.emit(MessagesEvent("Restored $restored message(s)"))
        }
    }

    companion object {
        const val LIMIT = 300
    }
}
