package com.okb.whatsappbridge.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okb.whatsappbridge.domain.model.MonitoredGroup
import com.okb.whatsappbridge.domain.repository.GroupRepository
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupsViewModel(
    private val repository: GroupRepository,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val groups: StateFlow<List<MonitoredGroup>?> =
        repository.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(name: String) {
        viewModelScope.launch {
            if (repository.addAuthorizedGroup(name, clock())) logger.info("Groups", "Group authorized: \"${name.trim()}\"")
        }
    }

    fun setAuthorized(group: MonitoredGroup, authorized: Boolean) {
        viewModelScope.launch {
            repository.setAuthorized(group.id, authorized)
            logger.info("Groups", "Group \"${group.name}\" ${if (authorized) "authorized" else "de-authorized"}")
        }
    }

    fun delete(group: MonitoredGroup) {
        viewModelScope.launch {
            repository.delete(group.id)
            logger.info("Groups", "Group \"${group.name}\" removed from list")
        }
    }
}
