package ai.vox.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for whether the user has started a VOX session.
 * Screen reading, automation and notification access are all gated on [active];
 * nothing is read or performed outside a session the user started manually.
 */
object SessionState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    val isActive: Boolean get() = _active.value

    fun start() { _active.value = true }
    fun stop() { _active.value = false }

    fun append(line: String) { _log.value = (_log.value + line).takeLast(200) }
}
