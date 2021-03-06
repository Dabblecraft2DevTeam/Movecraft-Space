package io.github.eirikh1996.movecraftspace.expansion.hyperspace.command

import io.github.eirikh1996.movecraftspace.Settings
import io.github.eirikh1996.movecraftspace.expansion.ExpansionManager
import io.github.eirikh1996.movecraftspace.expansion.hyperspace.HyperspaceBeacon
import io.github.eirikh1996.movecraftspace.expansion.hyperspace.HyperspaceExpansion
import io.github.eirikh1996.movecraftspace.expansion.hyperspace.HyperspaceManager
import io.github.eirikh1996.movecraftspace.expansion.hyperspace.HyperspaceTravelEntry
import io.github.eirikh1996.movecraftspace.objects.ImmutableVector
import io.github.eirikh1996.movecraftspace.objects.StarCollection
import io.github.eirikh1996.movecraftspace.utils.MSUtils.COMMAND_NO_PERMISSION
import io.github.eirikh1996.movecraftspace.utils.MSUtils.COMMAND_PREFIX
import io.github.eirikh1996.movecraftspace.utils.MSUtils.ERROR
import io.github.eirikh1996.movecraftspace.utils.MSUtils.hitboxObstructed
import net.countercraft.movecraft.MovecraftLocation
import net.countercraft.movecraft.craft.CraftManager
import org.bukkit.Bukkit
import org.bukkit.Bukkit.getScheduler
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.boss.BarColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.lang.Math.pow
import java.lang.System.currentTimeMillis
import java.math.RoundingMode
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

object HyperspaceCommand : TabExecutor {
    val runnableMap = HashMap<UUID, BukkitRunnable>()
    val locationMap = HashMap<UUID, Location>()
    override fun onTabComplete(p0: CommandSender, p1: Command, p2: String, p3: Array<out String>): List<String> {
        var tabCompletions = listOf("travel", "createbeacon", "removebeacon")
        if (p3.size == 0)
            return tabCompletions
        if (p3[0].equals("removebeacon", true)) {
            val links = ArrayList<String>()
            HyperspaceManager.beaconLocations.forEach { b -> links.add(b.originName + "-" + b.destinationName) }
            tabCompletions = links
        }
        return tabCompletions.filter { s -> s.startsWith(p3[p3.size - 1]) }
    }

    override fun onCommand(p0: CommandSender, p1: Command, p2: String, p3: Array<out String>): Boolean {
        if (!p1.name.equals("HyperSpace", true)) {
            return false
        }
        if (p0 !is Player) {
            p0.sendMessage(COMMAND_PREFIX + ERROR + "You must be player to use this command")
            return true
        }
        if (p3.size == 0) {
            return true
        }
        if (p3[0].equals("travel", true)) {
            val craft = CraftManager.getInstance().getCraftByPlayer(p0)
            if (craft == null) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + "You are not commanding a space craft")
                return true
            }
            val hitBox = craft.hitBox
            var foundLoc : Location? = null
            var destination : Location? = null
            var str = ""
            for (beacon in HyperspaceManager.beaconLocations) {
                if (beacon.origin.world!!.equals(craft.w) && beacon.origin.distance(hitBox.midPoint.toBukkit(craft.w)) <= HyperspaceExpansion.instance.config.getInt("Beacon range")) {
                    foundLoc = beacon.origin
                    destination = beacon.destination
                    str = beacon.originName + "-" + beacon.destinationName
                    break
                }
                if (beacon.destination.world!!.equals(craft.w) && beacon.destination.distance(hitBox.midPoint.toBukkit(craft.w)) <= HyperspaceExpansion.instance.config.getInt("Beacon range")) {
                    foundLoc = beacon.destination
                    destination = beacon.origin
                    str = beacon.destinationName + "-" + beacon.originName
                    break
                }
            }
            if (foundLoc == null) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + "You are not within the range of a hyperspace beacon")
                return true
            }
            var foundFuelContainer : InventoryHolder? = null
            val hypermatter = Material.getMaterial(HyperspaceExpansion.instance.config.getString("Hypermatter", "EMERALD")!!.toUpperCase())!!
            for (l in hitBox) {
                val b = l.toBukkit(craft.w).block
                if (b.type != Material.FURNACE)
                    continue
                val testHolder = b.state as InventoryHolder
                if (!testHolder.inventory.contains(hypermatter))
                    continue
                foundFuelContainer = testHolder
                break
            }
            if (foundFuelContainer == null) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + "No hypermatter found in the furnaces")
                return true
            }
            val stack = foundFuelContainer.inventory.getItem(foundFuelContainer.inventory.first(hypermatter))!!
            if (stack.amount == 1) {
                foundFuelContainer.inventory.remove(stack)
            } else {
                stack.amount--
            }
            destination = destination!!
            if (craft.cruising)
                craft.cruising = false
            val entry = HyperspaceTravelEntry(craft, foundLoc, destination)
            entry.progressBar.addPlayer(p0)
            entry.progressBar.setTitle(str + " Warmup 0.0%")
            val notifyP = craft.notificationPlayer!!
            val runnable = object : BukkitRunnable() {
                val warmupTime = HyperspaceExpansion.instance.config.getInt("Hyperspace travel warmup", 60)
                val timeStarted = currentTimeMillis()
                override fun run() {
                    notifyP.playSound(notifyP.location, HyperspaceExpansion.instance.hyperspaceChargeSound, 0.1f, 0f)
                    val timePassed = (currentTimeMillis() - timeStarted) / 1000
                    val progress = min(timePassed.toDouble() / warmupTime.toDouble(), 1.0)
                    val percent = progress * 100
                    entry.progressBar.setTitle(str + " Warmup " + percent.toBigDecimal().setScale(2, RoundingMode.UP) + "%")
                    entry.progressBar.progress = progress
                    if (timePassed <= warmupTime)
                        return
                    notifyP.sendMessage("Initiating hyperspace jump")
                    notifyP.playSound(notifyP.location, HyperspaceExpansion.instance.hyperspaceEnterSound, 15f, 0f)
                    cancel()
                    val hyperspaceWorld = HyperspaceExpansion.instance.hyperspaceWorld
                    val bounds = ExpansionManager.worldBoundrary(hyperspaceWorld)
                    val minX = (bounds[0] + (hitBox.xLength / 2)) + 10
                    val maxX = (bounds[1] - (hitBox.xLength / 2)) - 10
                    val minZ = (bounds[2] + (hitBox.zLength / 2)) + 10
                    val maxZ = (bounds[3] - (hitBox.zLength / 2)) - 10
                    var x = Random.nextInt(minX, maxX)
                    var z = Random.nextInt(minZ, maxZ)
                    var hitboxObstructed = true
                    val midpoint = hitBox.midPoint
                    while (hitboxObstructed) {
                        hitboxObstructed = getScheduler().callSyncMethod(HyperspaceExpansion.instance.plugin, { hitboxObstructed(craft, null, hyperspaceWorld, MovecraftLocation(x - midpoint.x , 0, z - midpoint.z)) }).get()
                        if (!hitboxObstructed)
                            break
                        x = Random.nextInt(minX, maxX)
                        z = Random.nextInt(minZ, maxZ)
                    }
                    entry.progressBar.progress = 0.0
                    entry.progressBar.color = BarColor.BLUE
                    val dx = x - midpoint.x
                    val dz = z - midpoint.z
                    val runnable = runnableMap.remove(craft.notificationPlayer!!.uniqueId)
                    if (runnable != null) {
                        runnable.cancel()
                    }
                    HyperspaceManager.craftsWithinBeaconRange.remove(craft)
                    craft.translate(hyperspaceWorld, dx, 0, dz)
                    HyperspaceManager.addEntry(craft, entry)
                }

            }
            runnableMap.put(p0.uniqueId, runnable)
            runnable.runTaskTimerAsynchronously(HyperspaceExpansion.instance.plugin, 0, 1)

        } else if (p3[0].equals("createbeacon", true)) {
            if (!p0.hasPermission("movecraftspace.command.hyperspace.createbeacon")) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + COMMAND_NO_PERMISSION)
                return true
            }
            if (locationMap.containsKey(p0.uniqueId)) {
                val origin = locationMap.remove(p0.uniqueId)!!
                val second = p0.location.clone()
                second.y = 127.0
                val originStar = StarCollection.closestStar(origin)!!
                val destinationStar = StarCollection.closestStar(second)
                val maxDistance = HyperspaceExpansion.instance.config.getInt("Max distance from star systems", 3000)
                if (destinationStar == null) {
                    p0.sendMessage(COMMAND_PREFIX + ERROR + "No stars are present in this space world")
                    return true
                }
                if (destinationStar.loc.distance(ImmutableVector.fromLocation(second)) > destinationStar.radius() + maxDistance) {
                    p0.sendMessage(COMMAND_PREFIX + ERROR + "Beacon placement is too far from the nearest star system " + destinationStar.name)
                    return true
                }
                p0.sendMessage(COMMAND_PREFIX + "Successfully created hyperspace link between " + originStar.name + " and " + destinationStar.name)
                createBeacon(origin)
                createBeacon(second)
                HyperspaceManager.beaconLocations.add(HyperspaceBeacon(originStar.name, origin, destinationStar.name, second))
                HyperspaceManager.saveFile()
                return true
            }
            val origin = p0.location.clone()
            origin.y = 127.0
            val originStar = StarCollection.closestStar(origin)
            if (originStar == null) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + "No stars are present in this space world")
                return true
            }
            val maxDistance = HyperspaceExpansion.instance.config.getInt("Max distance from star systems", 3000)
            if (originStar.loc.distance(ImmutableVector.fromLocation(origin)) > originStar.radius() + maxDistance) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + "Beacon placement is too far from the nearest star system " + originStar.name)
                return true
            }
            p0.sendMessage(COMMAND_PREFIX + "Set the first location for the hyperspace beacon. Select a second location in a different start system")
            locationMap.put(p0.uniqueId, origin)
        } else if (p3[0].equals("removebeacon", true)) {
            if (!p0.hasPermission("movecraftspace.command.hyperspace.removebeacon")) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + COMMAND_NO_PERMISSION)
                return true
            }
            if (p3.size < 2) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + "You must specify a beacon link")
                return true
            }
            val split = p3[1].split("-")
            var foundBeacon : HyperspaceBeacon? = null
            for (b in HyperspaceManager.beaconLocations) {
                if (!split[0].equals(b.originName, true) || !split[1].equals(b.destinationName, true))
                    continue
                foundBeacon = b
                break
            }
            if (foundBeacon == null) {
                p0.sendMessage(COMMAND_PREFIX + ERROR + "No such beacon link exists: " + p3[1])
                return true
            }
            val queue = LinkedList<ImmutableVector>()
            val visited = HashSet<ImmutableVector>()
            queue.add(ImmutableVector.fromLocation(foundBeacon.origin))
            while (!queue.isEmpty()) {
                val node = queue.poll()
                if (visited.contains(node))
                    continue
                visited.add(node)
                for (shift in SHIFTS) {
                    val test = node.add(shift)
                    if (test.toLocation(foundBeacon.origin.world!!).block.type.name.endsWith("AIR"))
                        continue
                    queue.add(test)
                }
            }
            visited.forEach { l -> l.toLocation(foundBeacon.origin.world!!).block.type = Material.AIR }
            queue.add(ImmutableVector.fromLocation(foundBeacon.destination))
            while (!queue.isEmpty()) {
                val node = queue.poll()
                if (visited.contains(node))
                    continue
                visited.add(node)
                for (shift in SHIFTS) {
                    val test = node.add(shift)
                    if (test.toLocation(foundBeacon.destination.world!!).block.type.name.endsWith("AIR"))
                        continue
                    queue.add(test)
                }
            }
            visited.forEach { l -> l.toLocation(foundBeacon.destination.world!!).block.type = Material.AIR }
            p0.sendMessage(COMMAND_PREFIX + "Sucessfully removed beacon link " + foundBeacon.originName + "-" + foundBeacon.destinationName)
            HyperspaceManager.beaconLocations.remove(foundBeacon)
            HyperspaceManager.saveFile()
        }
        return true
    }

    private fun createBeacon(loc : Location) {
        for (y in loc.blockY - 1..loc.blockY + 1) {
            for (x in loc.blockX - 30 .. loc.blockX + 30)
                for (z in loc.blockZ - 30 .. loc.blockZ + 30) {
                    val dSquared = (loc.blockX - x) * (loc.blockX - x) + (loc.blockZ - z) * (loc.blockZ - z)
                    if (abs(dSquared - 900) > 30 && abs(dSquared - 625) > 25 && abs(dSquared - 400) > 20 && abs(dSquared - 225) > 15 && abs(dSquared - 100) > 10) {
                        continue
                    }
                    val block = loc.world!!.getBlockAt(x, y, z)
                    block.type = if (Settings.IsLegacy) {
                        Material.getMaterial("STAINED_CLAY")!!
                    } else {
                        Material.WHITE_TERRACOTTA
                    }

                }
        }
        for (y in loc.blockY - 1..loc.blockY + 1) {
            for (x in loc.blockX - 30 .. loc.blockX + 30) {
                val block = loc.world!!.getBlockAt(x, y, loc.blockZ)
                block.type = if (Settings.IsLegacy) {
                    Material.getMaterial("STAINED_CLAY")!!
                } else {
                    Material.WHITE_TERRACOTTA
                }
            }
        }
        for (y in loc.blockY - 1..loc.blockY + 1) {
            for (z in loc.blockZ - 30 .. loc.blockZ + 30) {
                val block = loc.world!!.getBlockAt(loc.blockX, y, z)
                block.type = if (Settings.IsLegacy) {
                    Material.getMaterial("STAINED_CLAY")!!
                } else {
                    Material.WHITE_TERRACOTTA
                }
            }
        }
        for (y in loc.blockY - 10 .. loc.blockY + 40) {
            val diff = (loc.blockY + 40) - y
            val radius = diff / 12
            for (x in loc.blockX - 2 .. loc.blockX + 2)
                for (z in loc.blockZ - 2 .. loc.blockZ + 2) {

                    val dSquared = (loc.blockX - x) * (loc.blockX - x) + (loc.blockZ - z) * (loc.blockZ - z)
                    if (abs(dSquared - (radius * radius)) > radius) {
                        continue
                    }
                    val block = loc.world!!.getBlockAt(x, y, z)
                    block.type = if (Settings.IsLegacy) {
                        Material.getMaterial("STAINED_CLAY")!!
                    } else {
                        Material.WHITE_TERRACOTTA
                    }

                }
        }
        val sLoc = loc.clone().subtract(0.0, 15.0, 0.0)
        for (y in sLoc.blockY - 15 .. sLoc.blockY + 15) {
            for (x in sLoc.blockX - 15 .. sLoc.blockX + 15)
                for (z in sLoc.blockZ - 15 .. sLoc.blockZ + 15) {
                    val dSquared = (sLoc.blockX - x) * (sLoc.blockX - x) + (sLoc.blockY - y) * (sLoc.blockY - y) + (sLoc.blockZ - z) * (sLoc.blockZ - z)
                    if (abs(dSquared - 255) > 15) {
                        continue
                    }
                    val block = loc.world!!.getBlockAt(x, y, z)
                    block.type = if (Settings.IsLegacy) {
                        Material.getMaterial("STAINED_CLAY")!!
                    } else {
                        Material.WHITE_TERRACOTTA
                    }

                }
        }
    }

    private val SHIFTS = arrayOf(
        ImmutableVector(1,1,0),
        ImmutableVector(1,0,0),
        ImmutableVector(1,-1,0),
        ImmutableVector(-1,1,0),
        ImmutableVector(-1,0,0),
        ImmutableVector(-1,-1,0),
        ImmutableVector(0,1,1),
        ImmutableVector(0,0,1),
        ImmutableVector(0,-1,1),
        ImmutableVector(0,1,-1),
        ImmutableVector(0,0,-1),
        ImmutableVector(0,-1,-1),
        ImmutableVector(0,1,0),
        ImmutableVector(0,-1,0),
        ImmutableVector(1, 0, 1),
        ImmutableVector(-1, 0, 1),
        ImmutableVector(1, 0, -1),
        ImmutableVector(-1, 0, -1)
    )
}