package io.github.eirikh1996.movecraftspace.expansion.hyperspace.objects

import io.github.eirikh1996.movecraftspace.expansion.hyperspace.managers.HyperspaceManager.progressBars
import net.countercraft.movecraft.craft.Craft
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.util.Vector

data class HyperspaceTravelEntry(val craft : Craft, val origin : Location, val destination : Location, val beaconTravel : Boolean = false) {
    var progress = Vector(0,0,0)
    var lastTeleportTime = System.currentTimeMillis()
    var stage = Stage.WARM_UP
    val progressBar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SEGMENTED_20)
    init {
        progressBars[craft] = progressBar
        progressBar.isVisible = true
    }

    enum class Stage {
        WARM_UP, TRAVEL, FINISHED
    }
}