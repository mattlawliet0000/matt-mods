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

object InventorySafe {
    private val gson = Gson()
    private val configFolder = File("config/matt_mods").apply { mkdirs() }
    private val backupDir = File(configFolder, "death_backups").apply { mkdirs() }

    data class DeathBackup(
        val timestamp: String,
        val items: List<ItemStackSnapshot>,
        val experience: Int,
        val deathLocation: DeathLocation
    )

    data class ItemStackSnapshot(
        val item: String,
        val count: Int,
        val nbt: String? = null
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

        // Save all inventory slots (including armor and offhand)
        for (i in 0 until inventory.size()) {
            val stack = inventory.getStack(i)
            if (!stack.isEmpty) {
                // Use the Codec system with NbtOps to encode the item stack
                val nbtResult = ItemStack.CODEC.encodeStart<NbtElement>(NbtOps.INSTANCE, stack)

                val nbtString = if (nbtResult.isSuccess) {
                    nbtResult.getOrThrow().toString()
                } else {
                    // Fallback: save basic item info without NBT
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
        val deathBackup = DeathBackup(
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            items = items,
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


    fun loadBackup(player: ServerPlayerEntity, index: Int): Boolean {
        val playerDir = File(backupDir, player.uuidAsString)
        if (!playerDir.exists()) return false

        val backups = playerDir.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.nameWithoutExtension.toLongOrNull() }
            ?.reversed() ?: return false

        if (index < 0 || index >= backups.size) return false

        val backupFile = backups[index]
        val json = backupFile.readText()
        val backup = gson.fromJson(json, DeathBackup::class.java)

        // Clear current inventory
        player.inventory.clear()

        // Restore items
        backup.items.forEach { snapshot ->
            try {
                // Use readCompound instead of parse
                val nbtElement = StringNbtReader.readCompound(snapshot.nbt)
                val decodeResult = ItemStack.CODEC.parse<NbtElement>(NbtOps.INSTANCE, nbtElement)

                if (decodeResult.isSuccess) {
                    val stack = decodeResult.getOrThrow()

                    // Try to add to inventory, drop if full
                    if (!player.inventory.insertStack(stack)) {
                        player.dropItem(stack, false)
                    }
                } else {
                    println("Failed to decode item: ${decodeResult.error().get().message()}")
                }
            } catch (e: Exception) {
                println("Failed to restore item ${snapshot.item}: ${e.message}")
                // Fallback: you could create a basic item here using just the item ID
                // without NBT data if the complex restoration fails
            }
        }

        // Restore experience
        player.experienceLevel = backup.experience

        // Delete the backup after loading
        backupFile.delete()

        return true
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
        val playerDir = File(backupDir, player.uuidAsString)
        return playerDir.listFiles { file -> file.extension == "json" }?.size ?: 0
    }

    fun hasBackups(player: ServerPlayerEntity): Boolean {
        return getBackupCount(player) > 0
    }
}