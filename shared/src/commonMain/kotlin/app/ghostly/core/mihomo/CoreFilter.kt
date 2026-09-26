package app.ghostly.core.mihomo

import app.ghostly.core.model.CoreType
import app.ghostly.core.model.Profile

/**
 * One core at a time: with Xray only what Xray runs is shown and used, with mihomo only what mihomo
 * runs. Clash/mihomo profiles are mihomo-only, full Xray-JSON configs are Xray-only, plain links
 * go with the chosen core. Returns null when nothing of the profile fits.
 */
fun Profile.visibleFor(core: CoreType): Profile? = when (core) {
    CoreType.XRAY -> if (mihomo != null) null else this
    CoreType.MIHOMO -> {
        val runnable = servers.filter { it.config == null }
        if (servers.isNotEmpty() && runnable.isEmpty()) null else copy(servers = runnable)
    }
}
