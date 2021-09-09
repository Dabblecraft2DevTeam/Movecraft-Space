package io.github.eirikh1996.movecraftspace.expansion.hyperspace.events.beacon

import io.github.eirikh1996.movecraftspace.expansion.hyperspace.objects.HyperspaceBeacon
import org.bukkit.event.HandlerList

class BeaconRemoveEvent(beacon: HyperspaceBeacon) : BeaconEvent(beacon) {
    override fun getHandlers(): HandlerList {
        return handlerList
    }

    companion object {
        @JvmStatic val handlerList = HandlerList()
    }

}