package ai.vox.app.core.session

import ai.vox.app.core.model.Capability
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Starts and stops one kind of access. Implementations must release *everything* in [stop]. */
interface CapabilityController {
    val capability: Capability
    suspend fun start(): Result<Unit>
    suspend fun stop()
}

data class SessionState(
    val active: Boolean = false,
    val enabled: Set<Capability> = emptySet(),
    val startedAtMs: Long? = null,
)

/**
 * The single source of truth for "is Vox allowed to see/hear anything right now".
 * Nothing runs unless [activate] was called by the user, and [end] always stops every capability.
 */
class SessionManager(
    controllers: List<CapabilityController>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUsageReset: () -> Unit = {},
) {
    private val byCap = controllers.associateBy { it.capability }
    private val lock = Mutex()
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    suspend fun activate() = lock.withLock {
        if (_state.value.active) return@withLock
        onUsageReset()
        _state.value = SessionState(active = true, enabled = emptySet(), startedAtMs = clock())
    }

    /** Enable one capability. The caller must already have obtained any Android permission/consent it needs. */
    suspend fun enable(cap: Capability): Result<Unit> = lock.withLock {
        val s = _state.value
        if (!s.active) return@withLock Result.failure(IllegalStateException("Activate Vox first."))
        if (cap in s.enabled) return@withLock Result.success(Unit)
        val controller = byCap[cap] ?: return@withLock Result.failure(IllegalStateException("$cap is not available."))
        val result = runCatching { controller.start() }.getOrElse { Result.failure(it) }
        if (result.isSuccess) _state.update { it.copy(enabled = it.enabled + cap) }
        result
    }

    suspend fun disable(cap: Capability) = lock.withLock {
        if (cap !in _state.value.enabled) return@withLock
        _state.update { it.copy(enabled = it.enabled - cap) } // flip the indicator first
        withContext(NonCancellable) { runCatching { byCap[cap]?.stop() } }
    }

    /** Ends the session: marks it inactive immediately, then stops every controller, even if some fail. */
    suspend fun end() = withContext(NonCancellable) {
        lock.withLock {
            if (!_state.value.active && _state.value.enabled.isEmpty()) return@withLock
            _state.value = SessionState() // indicators drop to "nothing accessed" before teardown runs
            // Stop all controllers, not only those marked enabled, so a half-started one can't leak.
            for (c in byCap.values.reversed()) runCatching { c.stop() }
        }
    }

    fun isEnabled(cap: Capability) = cap in _state.value.enabled
}
