package app.ghostly.core.mihomo

import app.ghostly.core.vpn.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** The Clash profile the selectors belong to: its id (picks are stored per profile) and config. */
data class GroupSource(val profileId: String, val config: JsonObject)

/**
 * Proxy groups (selectors) of a Clash/mihomo profile for the UI.
 *
 * Before connecting they come straight from the profile's config, so the user can set them up
 * first; while mihomo runs they come from its API (live choice of automatic groups, delays).
 * Every manual pick is remembered per profile ([savePick]) and applied again on each start —
 * the core itself forgets them between runs.
 */
class MihomoGroups(
    private val backend: DualCoreBackend?,
    private val scope: CoroutineScope,
    private val pingUrl: () -> String,
    private val source: StateFlow<GroupSource?>,
    private val picks: StateFlow<Map<String, Map<String, String>>>,
    private val savePick: (profileId: String, group: String, member: String) -> Unit,
) {
    private val live = MutableStateFlow<List<ProxyGroupInfo>>(emptyList())
    private val running = MutableStateFlow(false)

    val groups: StateFlow<List<ProxyGroupInfo>> = combine(live, running, source, picks) { l, run, src, p ->
        if (run && l.isNotEmpty()) l else src?.let { staticGroups(it.config, p[it.profileId].orEmpty()) }.orEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    private var poll: Job? = null

    init {
        if (backend != null) scope.launch {
            backend.state.collect { s ->
                if (s is VpnState.Connected && backend.onMihomo) start() else stop()
            }
        }
    }

    private fun api(): MihomoApi? = backend?.takeIf { it.onMihomo }?.mihomo?.api

    private fun start() {
        if (poll?.isActive == true) return
        running.value = true
        poll = scope.launch(Dispatchers.Default) {
            restorePicks()
            while (isActive) {
                refresh()
                delay(5_000)
            }
        }
    }

    private fun stop() {
        poll?.cancel()
        poll = null
        running.value = false
        live.value = emptyList()
    }

    /** The core starts with the config's defaults: put the user's saved choices back. */
    private suspend fun restorePicks() {
        val api = api() ?: return
        val src = source.value ?: return
        val saved = picks.value[src.profileId].orEmpty()
        if (saved.isEmpty()) return
        val known = runCatching { api.groups() }.getOrDefault(emptyList()).associateBy { it.name }
        var changed = false
        saved.forEach { (group, member) ->
            val g = known[group] ?: return@forEach
            if (g.selectable && member in g.members && g.now != member) changed = api.select(group, member) || changed
        }
        if (changed) api.closeConnections()
    }

    suspend fun refresh() {
        val api = api() ?: return
        live.value = runCatching { api.groups() }.getOrDefault(live.value)
    }

    fun select(group: String, member: String) {
        source.value?.let { savePick(it.profileId, group, member) }
        scope.launch(Dispatchers.Default) {
            val api = api() ?: return@launch
            // Optimistic: the row moves at once, the next refresh confirms.
            live.update { list -> list.map { if (it.name == group) it.copy(now = member) else it } }
            if (api.select(group, member)) api.closeConnections()
            refresh()
        }
    }

    fun test(group: String) {
        if (group in _testing.value) return
        scope.launch(Dispatchers.Default) {
            val api = api() ?: return@launch
            _testing.update { it + group }
            try {
                api.testGroup(group, pingUrl())
                refresh()
            } finally {
                _testing.update { it - group }
            }
        }
    }

    /** Can [test] do anything right now (the core must run to measure). */
    val canTest: Boolean get() = running.value

    companion object {
        /** Groups as the config declares them, with the saved picks (or the first member) as the choice. */
        fun staticGroups(cfg: JsonObject, saved: Map<String, String>): List<ProxyGroupInfo> {
            val declared = MihomoProfiles.groups(cfg)
            val hidden = MihomoProfiles.hiddenGroups(cfg)
            return declared.filterKeys { it !in hidden }.map { (name, tm) ->
                val (type, members) = tm
                val info = ProxyGroupInfo(
                    name = name,
                    type = when (type) {
                        "select" -> "Selector"
                        "url-test" -> "URLTest"
                        "fallback" -> "Fallback"
                        "load-balance" -> "LoadBalance"
                        "relay" -> "Relay"
                        "smart" -> "Smart"
                        else -> type
                    },
                    now = null,
                    members = members,
                    delays = emptyMap(),
                    nestedGroups = members.filter { it in declared }.toSet(),
                )
                info.copy(now = if (info.selectable) saved[name]?.takeIf { it in members } ?: members.firstOrNull() else null)
            }
        }
    }
}
