package io.github.eirikh1996.movecraftspace.listener

import io.github.eirikh1996.movecraftspace.MovecraftSpace
import io.github.eirikh1996.movecraftspace.Settings
import io.github.eirikh1996.movecraftspace.expansion.ExpansionManager
import io.github.eirikh1996.movecraftspace.objects.Planet
import io.github.eirikh1996.movecraftspace.objects.PlanetCollection
import io.github.eirikh1996.movecraftspace.objects.StarCollection
import io.github.eirikh1996.movecraftspace.utils.MSUtils.hitboxObstructed
import net.countercraft.movecraft.Movecraft
import net.countercraft.movecraft.MovecraftChunk
import net.countercraft.movecraft.MovecraftLocation
import net.countercraft.movecraft.craft.ChunkManager
import net.countercraft.movecraft.craft.Craft
import net.countercraft.movecraft.craft.CraftManager
import net.countercraft.movecraft.events.CraftPreTranslateEvent
import net.countercraft.movecraft.events.CraftReleaseEvent
import net.countercraft.movecraft.events.CraftSinkEvent
import net.countercraft.movecraft.mapUpdater.MapUpdateManager
import net.countercraft.movecraft.mapUpdater.update.ExplosionUpdateCommand
import net.countercraft.movecraft.mapUpdater.update.UpdateCommand
import net.countercraft.movecraft.utils.BitmapHitBox
import net.countercraft.movecraft.utils.MathUtils
import org.bukkit.Bukkit
import org.bukkit.Bukkit.getScheduler
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.lang.Integer.max
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.Future
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.min
import kotlin.random.Random

object MovecraftListener : Listener {
    var SET_CANCELLED : Method?
    init {
        try {
            SET_CANCELLED = ChunkUnloadEvent::class.java.getDeclaredMethod("setCancelled")
        } catch (e : Exception) {
            SET_CANCELLED = null
        }

    }

    @EventHandler
    fun onCraftPreTranslate (event : CraftPreTranslateEvent) {
        val craft = event.craft
        if (!craft.type.canSwitchWorld) {
            return
        }
        val hitBox = craft.hitBox
        var planet : Planet? = PlanetCollection.getCorrespondingPlanet(craft.w)
        if (planet == null) {
            for (ml in hitBox) {
                val destination = ml.translate(event.dx, event.dy, event.dz)
                if (craft.hitBox.contains(destination)) {
                    continue
                }
                planet = PlanetCollection.getPlanetAt(destination.toBukkit(craft.w))
                if (planet == null) {
                    continue
                }
                break
            }
        }
        if (planet == null) {
            return
        }
        val destWorld = if (planet.destination.equals(craft.w)) {
            planet.space;
        } else {
            planet.destination
        }
        if (craft.w.equals(planet.destination) && hitBox.maxY < planet.exitHeight) {
            return
        }
        if (destWorld.equals(planet.destination)) {
            craft.notificationPlayer!!.sendMessage("Entering " + destWorld.name)
        } else {
            craft.notificationPlayer!!.sendMessage("Exiting " + craft.w.name)
        }
        if (craft.cruising) {
            craft.cruising = false
        }
        val midpoint = craft.hitBox.midPoint
        val displacement = if (destWorld.equals(planet.destination)) {
            val y = planet.exitHeight - (hitBox.yLength / 2) - 5
            var destLoc : MovecraftLocation? = null
            while (destLoc == null) {
                val bounds = ExpansionManager.worldBoundrary(destWorld)
                val minX = (bounds[0] + (hitBox.xLength / 2)) + 10
                val maxX = (bounds[1] - (hitBox.xLength / 2)) - 10
                val minZ = (bounds[2] + (hitBox.zLength / 2)) + 10
                val maxZ = (bounds[3] - (hitBox.zLength / 2)) - 10
                val x = Random.nextInt(minX, maxX)
                val z = Random.nextInt(minZ, maxZ)
                val test = MovecraftLocation(x, y, z)

                if (!MathUtils.withinWorldBorder(destWorld, test)) {
                    continue
                }

                if (craft.notificationPlayer != null && !ExpansionManager.allowedArea(craft.notificationPlayer!!, test.toBukkit(destWorld))) {
                    continue
                }
                val diff = test.subtract(midpoint)
                val chunks = ChunkManager.getChunks(craft.hitBox, destWorld, diff.x, diff.y, diff.z)
                MovecraftChunk.addSurroundingChunks(chunks, 3)
                ChunkManager.syncLoadChunks(chunks)
                val testType = test.toBukkit(destWorld).block.type
                if (!testType.name.endsWith("AIR") && !craft.type.passthroughBlocks.contains(testType)) {
                    continue
                }
                val obstructed = getScheduler().callSyncMethod(MovecraftSpace.instance, { hitboxObstructed(craft, planet, destWorld, diff) }).get()
                if (obstructed)
                    continue
                destLoc = diff
            }
            destLoc
        } else {
            var destLoc : MovecraftLocation? = null
            while (destLoc == null) {
                val maxY = min(planet.center.y + planet.radius + 160, 250)
                val minY = max(planet.center.y - planet.radius - 160, 10)
                val x = Random.nextInt(planet.center.x - planet.radius - 160, planet.center.x + planet.radius + 160)
                val y = Random.nextInt(minY, maxY)
                val z = Random.nextInt(planet.center.z - planet.radius - 160, planet.center.z + planet.radius + 160)
                val test = MovecraftLocation(x, max(min(y, 252 - (craft.hitBox.yLength / 2)), (craft.hitBox.yLength / 2) + 3), z)
                if (planet.contains(test.toBukkit(destWorld))) {
                    continue
                }
                if (!MathUtils.withinWorldBorder(destWorld, test)) {
                    continue
                }
                if (craft.notificationPlayer != null && !ExpansionManager.allowedArea(craft.notificationPlayer!!, test.toBukkit(destWorld))) {
                    continue
                }
                if (StarCollection.getStarAt(test.toBukkit(destWorld)) != null)
                    continue
                val diff = test.subtract(midpoint)
                val chunks = ChunkManager.getChunks(craft.hitBox, destWorld, diff.x, diff.y, diff.z)
                MovecraftChunk.addSurroundingChunks(chunks, 3)
                ChunkManager.syncLoadChunks(chunks)
                val obstructed = getScheduler().callSyncMethod(MovecraftSpace.instance, { hitboxObstructed(craft, planet, destWorld, diff) }).get()
                if (obstructed)
                    continue
                destLoc = diff
            }
            destLoc
        }
        event.world = destWorld
        event.dx = displacement.x
        event.dy = displacement.y
        event.dz = displacement.z

    }

    @EventHandler(
        priority = EventPriority.MONITOR
    )
    fun onSink(event : CraftSinkEvent) {
        if (!Settings.ExplodeSinkingCraftsInWorlds.contains(event.craft.w.name)) {
            return
        }
        event.isCancelled = true
        val explosionLocations = HashSet<UpdateCommand>()
        val hitBox = event.craft.hitBox
        for (x in hitBox.minX..hitBox.maxX step 5) {
            for (y in hitBox.minY..hitBox.maxY step 5) {
                for (z in hitBox.minZ..hitBox.maxZ step 5) {
                    if (!hitBox.contains(x, y, z))
                        continue
                    explosionLocations.add(ExplosionUpdateCommand(Location(event.craft.w, x.toDouble(), y.toDouble(), z.toDouble()), 6f))
                }
            }
        }
        if (explosionLocations.isEmpty()) {
            explosionLocations.add(ExplosionUpdateCommand(hitBox.midPoint.toBukkit(event.craft.w), 6f))
        }
        val collapsed = BitmapHitBox(hitBox)
        hitBox.clear()
        CraftManager.getInstance().removeCraft(event.craft, CraftReleaseEvent.Reason.SUNK)
        MapUpdateManager.getInstance().scheduleUpdates(explosionLocations)
        object : BukkitRunnable() {
            override fun run() {
                event.craft.collapsedHitBox.addAll(collapsed.filter {
                        loc -> !loc.toBukkit(event.craft.w).block.type.name.endsWith("AIR")
                })
                Movecraft.getInstance().asyncManager.addWreck(event.craft)
            }

        }.runTaskLater(MovecraftSpace.instance, 3)
    }

    @EventHandler
    fun onRelease(event : CraftReleaseEvent) {
        if (event.reason == CraftReleaseEvent.Reason.FORCE) {
            return
        }
        val craft = event.craft
        for (ml in craft.hitBox) {
            val intersecting = PlanetCollection.intersectingOtherPlanetaryOrbit(ml.toBukkit(craft.w))
            if (intersecting == null)
                continue
            craft.notificationPlayer!!.sendMessage("You cannot release your craft here as the craft intersects with the planetary orbit of " + intersecting.name)
            event.isCancelled = true
            break
        }
    }


}