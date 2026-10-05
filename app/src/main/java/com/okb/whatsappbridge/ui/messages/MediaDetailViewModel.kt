package com.okb.whatsappbridge.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.model.MediaAttachment
import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.usecase.RecycleBinUseCase
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MessageDetailState(val message: BridgeMessage?, val media: List<MediaAttachment>)

class MediaDetailViewModel(
    private val messageId: String,
    messages: MessageRepository,
    media: MediaRepository,
    private val recycleBin: RecycleBinUseCase,
) : ViewModel() {
    val state: StateFlow<MessageDetailState> =
        combine(messages.observeById(messageId), media.observeForMessage(messageId)) { message, mediaList ->
            MessageDetailState(message, mediaList)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageDetailState(null, emptyList()))

    fun moveToRecycleBin(onDone: () -> Unit) {
        viewModelScope.launch {
            recycleBin.moveToBin(listOf(messageId))
            onDone()
        }
    }

    fun restore() {
        viewModelScope.launch { recycleBin.restore(listOf(messageId)) }
    }
}
