package com.okb.whatsappbridge.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.repository.MessageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _filter = MutableStateFlow<UploadStatus?>(null)
    val filter: StateFlow<UploadStatus?> = _filter.asStateFlow()

    val messages: StateFlow<List<BridgeMessage>?> = _filter
        .flatMapLatest { repository.observeRecent(it, LIMIT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFilter(status: UploadStatus?) {
        _filter.value = status
    }

    companion object {
        const val LIMIT = 300
    }
}
