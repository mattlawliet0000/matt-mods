package org.mattlawliet.matt_mods

import com.google.gson.Gson
import net.minecraft.item.ItemStack
import net.minecraft.nbt.StringNbtReader
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
//import net.minecraft.registry.RegistryKeys
//import net.minecraft.registry.entry.RegistryEntry
//import net.minecraft.registry.RegistryWrapper
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtElement
import dev.emi.trinkets.api.TrinketsApi

import net.minecraft.registry.Registries

object InventorySafe {
    private val gson = Gson()
    private val configFolder = File("config/matt_mods").apply { mkdirs() }
    private val backupDir = File(configFolder, "death_backups").apply { mkdirs() }
    private const val MAX_BACKUPS = 5

    private fun getPlayerBackupDir(player: ServerPlayerEntity): File {
        return File(backupDir, player.uuidAsString).apply { mkdirs() }
    }

    private fun getBackupFiles(player: ServerPlayerEntity): List<File> {
        val playerDir = getPlayerBackupDir(player)
        return playerDir.listFiles { file -> file.name.endsWith(".json") }?.toList() ?: emptyList()
    }

    data class DeathBackup(
        val timestamp: String,
        val items: List<ItemStackSnapshot>,
        val trinketItems: List<ItemStackSnapshot>,
        val experience: Int,
        val deathLocation: DeathLocation,
        val filename: String = "" // Add this field
    )

    data class ItemStackSnapshot(
        val item: String,
        val count: Int,
        val nbt: String? = null,
        val slotType: String? = null // Optional: to track slot type
    )

    data class DeathLocation(
        val dimension: String,
        val x: Double,
        val y: Double,
        val z: Double
    )

    fun createBackup(player: ServerPlayerEntity): DeathBackup? {
        val inventory = player.inventory
        val items = mutableListOf<ItemStackSnapshot>()
        val trinketItems = mutableListOf<ItemStackSnapshot>()

        var hasItems = false
        var totalItemCount = 0

        // Save all inventory slots (including armor and offhand)
        for (i in 0 until inventory.size()) {
            val stack = inventory.getStack(i)
            if (!stack.isEmpty) {
                hasItems = true
                totalItemCount += stack.count

                val nbtResult = ItemStack.CODEC.encodeStart<NbtElement>(NbtOps.INSTANCE, stack)

                val nbtString = if (nbtResult.isSuccess) {
                    nbtResult.getOrThrow().toString()
                } else {
                    println("Failed to encode item stack: ${nbtResult.error().get().message()}")
                    null
                }

                items.add(ItemStackSnapshot(
                    item = stack.item.toString(),
                    count = stack.count,
                    nbt = nbtString
                ))
            }
        }

        // Save Trinkets items
        try {
            val trinketComponent = TrinketsApi.getTrinketComponent(player)
            trinketComponent.ifPresent { component ->
                component.allEquipped.forEach { pair ->
                    val slotReference = pair.left
                    val stack = pair.right
                    if (!stack.isEmpty) {
                        hasItems = true
                        totalItemCount += stack.count

                        val nbtResult = ItemStack.CODEC.encodeStart<NbtElement>(NbtOps.INSTANCE, stack)

                        val nbtString = if (nbtResult.isSuccess) {
                            nbtResult.getOrThrow().toString()
                        } else {
                            println("Failed to encode trinket item stack: ${nbtResult.error().get().message()}")
                            null
                        }

                        trinketItems.add(ItemStackSnapshot(
                            item = stack.item.toString(),
                            count = stack.count,
                            nbt = nbtString,
                            slotType = "trinket:${slotReference.id}"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to save trinkets: ${e.message}")
        }

        // Don't save if inventory is completely empty (no items and no experience)
        if (!hasItems && player.experienceLevel == 0) {
            return null
        }

        // Check for duplicates by comparing with latest backup
        if (isDuplicateBackup(player, items, trinketItems, player.experienceLevel)) {
            println("Skipping duplicate backup for ${player.name.string}")
            return null
        }

        val deathBackup = DeathBackup(
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            items = items,
            trinketItems = trinketItems,
            experience = player.experienceLevel,
            deathLocation = DeathLocation(
                dimension = player.world.registryKey.value.toString(),
                x = player.x,
                y = player.y,
                z = player.z
            )
        )

        saveBackup(player, deathBackup)

        // Enforce 5 backup limit
        enforceBackupLimit(player)

        return deathBackup
    }

    private fun isDuplicateBackup(player: ServerPlayerEntity, newItems: List<ItemStackSnapshot>, newTrinkets: List<ItemStackSnapshot>, newExperience: Int): Boolean {
        val backups = listBackups(player)
        if (backups.isEmpty()) return false

        // Get the most recent backup (first in the reversed list)
        val latestBackup = backups.first().second

        // Compare all relevant data
        return latestBackup.items.size == newItems.size &&
                latestBackup.trinketItems.size == newTrinkets.size &&
                latestBackup.experience == newExperience &&
                // Simple content check - compare total item counts
                latestBackup.items.sumOf { it.count } == newItems.sumOf { it.count } &&
                latestBackup.trinketItems.sumOf { it.count } == newTrinkets.sumOf { it.count }
    }

    private fun enforceBackupLimit(player: ServerPlayerEntity) {
        val backupFiles = getBackupFiles(player)

        if (backupFiles.size > MAX_BACKUPS) {
            // Sort by modification time (oldest first) and remove excess
            val filesToDelete = backupFiles.sortedBy { it.lastModified() }
                .take(backupFiles.size - MAX_BACKUPS)

            filesToDelete.forEach { file ->
                if (file.delete()) {
                    println("Deleted old backup: ${file.name}")
                } else {
                    println("Failed to delete old backup: ${file.name}")
                }
            }

            println("Enforced backup limit for ${player.name.string}: deleted ${filesToDelete.size}, kept $MAX_BACKUPS")
        }
    }

    fun deleteBackup(player: ServerPlayerEntity, index: Int): Boolean {
        val backups = listBackups(player)
        if (index < 0 || index >= backups.size) return false

        val backup = backups[index].second
        val backupFile = File(getPlayerBackupDir(player), backup.filename)
        return backupFile.delete()
    }

    private fun saveBackup(player: ServerPlayerEntity, backup: DeathBackup) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val filename = "backup_$timestamp.json"

        val backupWithFilename = backup.copy(filename = filename) // Store filename

        val backupFile = File(getPlayerBackupDir(player), filename)
        backupFile.writeText(gson.toJson(backupWithFilename))
    }


    fun loadBackupExec(player: ServerPlayerEntity, backup: DeathBackup): Boolean {
        try {
            // Clear main inventory
            player.inventory.clear()

            // Restore main inventory items
            backup.items.forEachIndexed { index, itemSnapshot ->
                val stack = decodeItemStack(itemSnapshot)
                if (!stack.isEmpty && index < player.inventory.size()) {
                    player.inventory.setStack(index, stack)
                }
            }

            // Restore Trinkets items
            val trinketComponent = TrinketsApi.getTrinketComponent(player)
            trinketComponent.ifPresent { component ->
                // Clear existing trinkets
                component.allEquipped.forEach { pair ->
                    val slotReference = pair.left
                    //val _value = pair.right
                    slotReference.inventory.setStack(slotReference.index, ItemStack.EMPTY)
                }

                // Restore saved trinkets
                backup.trinketItems.forEach { itemSnapshot ->
                    val stack = decodeItemStack(itemSnapshot)
                    if (!stack.isEmpty) {
                        var equipped = false

                        // Try to find and use the original slot if we saved slot information
                        if (itemSnapshot.slotType != null && itemSnapshot.slotType.startsWith("trinket:")) {
                            val slotId = itemSnapshot.slotType.removePrefix("trinket:")
                            // Try to find the slot with this ID and equip the item there
                            component.allEquipped.forEach { pair ->
                                val slotReference = pair.left
                                if (slotReference.id == slotId) {
                                    slotReference.inventory.setStack(slotReference.index, stack)
                                    equipped = true
                                    return@forEach
                                }
                            }
                        }

                        // If we couldn't find the original slot, try to find any available slot
                        if (!equipped) {
                            component.allEquipped.forEach { pair ->
                                val slotReference = pair.left
                                val currentStack = slotReference.inventory.getStack(slotReference.index)
                                if (currentStack.isEmpty) {
                                    // Empty slot found - place the item here
                                    slotReference.inventory.setStack(slotReference.index, stack)
                                    equipped = true
                                    return@forEach
                                }
                            }
                        }

                        // If no trinket slot available, add to main inventory
                        if (!equipped) {
                            player.inventory.offer(stack, false)
                            println("Could not equip trinket ${stack.item}, added to main inventory")
                        }
                    }
                }
            }

            // Restore experience
            player.experienceLevel = backup.experience

            return true
        } catch (e: Exception) {
            println("Failed to load backup: ${e.message}")
            return false
        }
    }

    // NEW FUNCTION: Load backup by index
    fun loadBackup(player: ServerPlayerEntity, index: Int): Boolean {
        val backups = listBackups(player)
        if (index < 0 || index >= backups.size) {
            return false
        }

        val backup = backups[index].second
        return loadBackupExec(player, backup) // Call the existing function
    }

    private fun decodeItemStack(snapshot: ItemStackSnapshot): ItemStack {
        return if (snapshot.nbt != null) {
            try {
                val nbtElement = StringNbtReader.readCompound(snapshot.nbt)
                val result = ItemStack.CODEC.parse(NbtOps.INSTANCE, nbtElement)
                if (result.isSuccess) {
                    result.getOrThrow()
                } else {
                    // Fallback: create basic stack
                    ItemStack(Registries.ITEM.get(Identifier.of(snapshot.item)), snapshot.count)
                }
            } catch (e: Exception) {
                println("Failed to decode NBT for item ${snapshot.item}: ${e.message}")
                ItemStack(Registries.ITEM.get(Identifier.of(snapshot.item)), snapshot.count)
            }
        } else {
            ItemStack(Registries.ITEM.get(Identifier.of(snapshot.item)), snapshot.count)
        }
    }

    fun listBackups(player: ServerPlayerEntity): List<Pair<Int, DeathBackup>> {
        val backupFiles = getBackupFiles(player)

        // Sort by modification time (oldest first) and take only MAX_BACKUPS for display
        return backupFiles.sortedBy { it.lastModified() }
            .takeLast(MAX_BACKUPS) // Only show the most recent MAX_BACKUPS
            .mapIndexed { index, file ->
                try {
                    val backup = gson.fromJson(file.readText(), DeathBackup::class.java)
                    index to backup
                } catch (e: Exception) {
                    println("Failed to read backup file ${file.name}: ${e.message}")
                    null
                }
            }
            .filterNotNull()
            //.reversed() // Show newest first in the list
    }

    fun getBackupCount(player: ServerPlayerEntity): Int {
        return getBackupFiles(player).size
    }

}