package org.mattlawliet.matt_mods

import com.google.gson.Gson
import net.minecraft.item.ItemStack
import net.minecraft.nbt.StringNbtReader
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtElement
import net.minecraft.registry.RegistryWrapper
import dev.emi.trinkets.api.TrinketsApi

import net.minecraft.registry.Registries

object InventorySafe {
    private val gson = Gson()
    private val configFolder = File("config/matt_mods").apply { mkdirs() }
    private val backupDir = File(configFolder, "death_backups").apply { mkdirs() }

    data class DeathBackup(
        val timestamp: String,
        val items: List<ItemStackSnapshot>,
        val trinketItems: List<ItemStackSnapshot> = emptyList(), // Add this field
        val experience: Int,
        val deathLocation: DeathLocation
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

    fun createBackup(player: ServerPlayerEntity): DeathBackup {
        val inventory = player.inventory
        val items = mutableListOf<ItemStackSnapshot>()
        val trinketItems = mutableListOf<ItemStackSnapshot>()

        // Save all main inventory slots (including armor and offhand)
        for (i in 0 until inventory.size()) {
            val stack = inventory.getStack(i)
            if (!stack.isEmpty) {
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

        val deathBackup = DeathBackup(
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            items = items,
            trinketItems = trinketItems, // Add trinket items to backup
            experience = player.experienceLevel,
            deathLocation = DeathLocation(
                dimension = player.world.registryKey.value.toString(),
                x = player.x,
                y = player.y,
                z = player.z
            )
        )

        saveBackup(player, deathBackup)
        return deathBackup
    }

    private fun saveBackup(player: ServerPlayerEntity, backup: DeathBackup) {
        val playerDir = File(backupDir, player.uuidAsString).apply { mkdirs() }
        val backupFile = File(playerDir, "${System.currentTimeMillis()}.json")
        backupFile.writeText(gson.toJson(backup))
    }


    fun loadBackup(player: ServerPlayerEntity, backup: DeathBackup): Boolean {
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
                    val _value = pair.right
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
        return loadBackup(player, backup) // Call the existing function
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
        val playerDir = File(backupDir, player.uuidAsString)
        if (!playerDir.exists()) return emptyList()

        return playerDir.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.nameWithoutExtension.toLongOrNull() }
            ?.reversed()
            ?.mapIndexed { index, file ->
                val backup = gson.fromJson(file.readText(), DeathBackup::class.java)
                index to backup
            } ?: emptyList()
    }

    fun getBackupCount(player: ServerPlayerEntity): Int {
        return listBackups(player).size
    }

    fun hasBackups(player: ServerPlayerEntity): Boolean {
        return getBackupCount(player) > 0
    }
}