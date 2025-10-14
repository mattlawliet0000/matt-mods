package org.mattlawliet.matt_mods

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.server.network.ServerPlayerEntity
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
//import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
//import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
//import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
import net.minecraft.util.ActionResult
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.BedBlock
import net.minecraft.command.CommandSource
import net.minecraft.command.argument.EntityArgumentType
import java.util.concurrent.CompletableFuture


class Matt_mods : ModInitializer {

    private val backupCooldowns = mutableMapOf<String, Long>()
    private val COOLDOWN_TIME = 5000L // 5 seconds in milliseconds

    override fun onInitialize() {
        loadBranches()
        loadPlayerData()

        // Bed right-click backup with cooldown
        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            val blockState = world.getBlockState(hitResult.blockPos)

            if (blockState.block is BedBlock && player is ServerPlayerEntity && !world.isClient) {
                val uuid = player.uuidAsString
                val currentTime = System.currentTimeMillis()
                val lastBackupTime = backupCooldowns[uuid]

                if (lastBackupTime == null || currentTime - lastBackupTime > COOLDOWN_TIME) {
                    try {
                        val backup = InventorySafe.createBackup(player)
                        if (backup != null){
                            player.sendMessage(Text.of("§6Inventory snapshot saved!"), false)
                            backupCooldowns[uuid] = currentTime
                            println("Bed-click backup created for ${player.name.string}")
                        }
                    } catch (e: Exception) {
                        player.sendMessage(Text.of("§cFailed to create backup!"), false)
                        println("Failed to create bed-click backup: ${e.message}")
                    }
                } else {
                    //val timeLeft = (COOLDOWN_TIME - (currentTime - lastBackupTime)) / 1000
                    //player.sendMessage(Text.of("§cPlease wait ${timeLeft}s before creating another backup"), false)
                }
            }
            ActionResult.PASS
        }

        // Prompt player on join if there are new changes
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val uuid = handler.player.uuidAsString
            val lastSeen = playerLastSeen[uuid]

            if (lastSeen == null) {
                // First time player - show if there are any changes
                if (branches[latestBranch]?.isNotEmpty() == true) {
                    handler.player.sendMessage(Text.of("§aWelcome! There are changes in the latest branch."))
                    handler.player.sendMessage(Text.of("§7Check with /changes"))
                }
                return@register
            }

            // Parse their last seen timestamp
            val lastSeenTime = java.time.LocalDateTime.parse(lastSeen, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // Check if any branches have changes after their last seen time
            val branchesWithNewChanges = branches.filter { (_, changeList) ->
                changeList.any { change ->
                    java.time.LocalDateTime.parse(change.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).isAfter(lastSeenTime)
                }
            }

            if (branchesWithNewChanges.isNotEmpty()) {
                val hasCritical = branchesWithNewChanges.values.any { changes ->
                    changes.any { it.critical }
                }
                if (hasCritical) {
                    handler.player.sendMessage(Text.of("§c§lCRITICAL CHANGES since you last logged in!"))
                } else {
                    handler.player.sendMessage(Text.of("§aNew changes since you last logged in"))
                }
                handler.player.sendMessage(Text.of("§7Check with /changes news"))
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            // Original changes command
            dispatcher.register(
                CommandManager.literal("changes")
                    .executes {
                        updatePlayerLastSeen(it)
                        getLatest(it)
                    }
                    .then(CommandManager.literal("help")
                        .executes { ctx ->
                            ctx.source.sendFeedback({
                                Text.of(
                                    """
                                    /changes add <text> - Add a change to the latest branch
                                    /changes addcritical <text> - Add a CRITICAL change (red)
                                    /changes new - Save latest as dated branch and reset latest
                                    /changes get [branch] - Show changes (defaults to latest)
                                    /changes news - Show changes since your last check
                                    /changes help - Show this help
                                    """.trimIndent()
                                )
                            }, false)
                            updatePlayerLastSeen(ctx)
                            1
                        }
                    )
                    .then(CommandManager.literal("add")
                        .requires { source -> source.hasPermissionLevel(2) }
                        .then(CommandManager.argument("text", StringArgumentType.greedyString())
                            .executes {
                                addChange(it)
                            }))
                    .then(CommandManager.literal("addCritical")
                        .requires { source -> source.hasPermissionLevel(2) }
                        .then(CommandManager.argument("text", StringArgumentType.greedyString())
                            .executes {
                                addChange(it, true)
                            }))
                    .then(CommandManager.literal("new")
                        .requires { source -> source.hasPermissionLevel(2) }
                        .executes {
                            updatePlayerLastSeen(it)
                            newBranch(it)
                        })
                    .then(CommandManager.literal("get")
                        .executes {
                            updatePlayerLastSeen(it)
                            getLatest(it)
                        }
                        .then(CommandManager.argument("branch", StringArgumentType.word())
                            .suggests { _, builder ->
                                branches.keys.forEach { builder.suggest(it) }
                                builder.buildFuture()
                            }
                            .executes {
                                updatePlayerLastSeen(it)
                                getBranch(it)
                            }
                        )
                    )
                    .then(CommandManager.literal("news")
                        .executes(::news)
                    )
            )

            // Death backup command
            dispatcher.register(
                CommandManager.literal("invbackup")
                    .requires { source -> source.hasPermissionLevel(2) }
                    .executes { ctx ->
                        ctx.source.sendFeedback({
                            Text.of(
                                """
                                §6Death Backup Commands:
                                §e/invbackup list§7 - List your death backups
                                §e/invbackup load <index>§7 - Load a specific backup
                                §e/invbackup help§7 - Show this help
                                """.trimIndent()
                            )
                        }, false)
                        1
                    }
                    //testCommit
                    .then(CommandManager.literal("list")
                        .executes { ctx ->
                            val player = ctx.source.playerOrThrow
                            val backups = InventorySafe.listBackups(player)

                            if (backups.isEmpty()) {
                                ctx.source.sendFeedback({ Text.of("§7No death backups found.") }, false)
                            } else {
                                ctx.source.sendFeedback({ Text.of("§6Your Death Backups:") }, false)
                                backups.forEach { (index, backup) ->
                                    val mainItemCount = backup.items.size
                                    val trinketItemCount = backup.trinketItems.size
                                    val totalItemCount = mainItemCount + trinketItemCount

                                    ctx.source.sendFeedback({
                                        Text.of("§7${index}. §f${backup.timestamp} §7(§f${totalItemCount} items§7 [§f${mainItemCount} inv + §f${trinketItemCount} trinkets§7], §f${backup.experience} levels§7)")
                                    }, false)
                                }
                                ctx.source.sendFeedback({
                                    Text.of("§7Use §e/invbackup load <index>§7 to restore a backup.")
                                }, false)
                            }
                            1
                        }
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .suggests(PlayerSuggestionProvider) // Add this line to provide suggestions
                            .executes { ctx -> // List backups for specified player
                                val targetPlayer = EntityArgumentType.getPlayer(ctx, "player")
                                val backups = InventorySafe.listBackups(targetPlayer)

                                if (backups.isEmpty()) {
                                    ctx.source.sendFeedback({ Text.of("§7No death backups found for ${targetPlayer.name.string}.") }, false)
                                } else {
                                    ctx.source.sendFeedback({ Text.of("§6Death Backups for ${targetPlayer.name.string}:") }, false)
                                    backups.forEach { (index, backup) ->
                                        val mainItemCount = backup.items.size
                                        val trinketItemCount = backup.trinketItems.size
                                        val totalItemCount = mainItemCount + trinketItemCount

                                        ctx.source.sendFeedback({
                                            Text.of("§7${index}. §f${backup.timestamp} §7(§f${totalItemCount} items§7 [§f${mainItemCount} inv + §f${trinketItemCount} trinkets§7], §f${backup.experience} levels§7)")
                                        }, false)
                                    }
                                    ctx.source.sendFeedback({
                                        Text.of("§7Use §e/invbackup load <index> ${targetPlayer.name.string}§7 to restore a backup.")
                                    }, false)
                                }
                                1
                            }
                        )
                    )
                    .then(CommandManager.literal("load")
                        .then(CommandManager.argument("index", IntegerArgumentType.integer(0))

                            .executes { ctx ->
                                val player = ctx.source.playerOrThrow
                                val index = IntegerArgumentType.getInteger(ctx, "index")

                                if (InventorySafe.loadBackup(player, index)) {
                                    ctx.source.sendFeedback({
                                        Text.of("§aSuccessfully loaded death backup #$index!")
                                    }, false)

                                    // Update the backup list after loading
                                    val remaining = InventorySafe.getBackupCount(player)
                                    if (remaining > 0) {
                                        ctx.source.sendFeedback({
                                            Text.of("§7You have §f$remaining§7 backup(s) remaining.")
                                        }, false)
                                    }
                                } else {
                                    ctx.source.sendFeedback({
                                        Text.of("§cFailed to load backup #$index. It may not exist.")
                                    }, false)
                                }
                                1
                            }
                        )
                    )
                    .then(CommandManager.literal("help")
                        .executes { ctx ->
                            ctx.source.sendFeedback({
                                Text.of(
                                    """
                                    §6Death Backup Help:
                                    §7This mod automatically saves your inventory when you die.
                                    §7Use §e/invbackup list§7 to see your saved backups.
                                    §7Use §e/invbackup load <index>§7 to restore a backup.
                                    §7Index §e0§7 is always your most recent death.
                                    §7Backups are automatically deleted after loading.
                                    """.trimIndent()
                                )
                            }, false)
                            1
                        }
                    )
            )
        }
    }

    companion object {
        private val gson = Gson()
        private val dateFormatter = DateTimeFormatter.ofPattern("M-d-yyyy")
        private val configFolder = Path.of("config", "matt_mods").toFile()
        private val branchesFile = File(configFolder, "changes_branches.json")
        private val playerFile = File(configFolder, "player_last_seen.json")
        data class Change(val text: String, val timestamp: String, val critical: Boolean = false)
        private var branches: MutableMap<String, MutableList<Change>> = ConcurrentHashMap()

        private var playerLastSeen: MutableMap<String, String> = ConcurrentHashMap()
        private var latestBranch = "latest"

        private fun addChange(ctx: CommandContext<ServerCommandSource>, critical: Boolean = false): Int {
            val text = StringArgumentType.getString(ctx, "text")
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val change = Change(text, timestamp, critical)
            branches[latestBranch]?.add(change)
            saveBranches()
            val criticalTag = if (critical) " §c[CRITICAL]" else ""
            ctx.source.sendFeedback({ Text.of("Added to $latestBranch$criticalTag: $text") }, false)
            return 1
        }

        private fun newBranch(ctx: CommandContext<ServerCommandSource>): Int {
            val latestList = branches[latestBranch] ?: mutableListOf()
            if (latestList.isEmpty()) {
                ctx.source.sendFeedback({ Text.of("No action performed. Latest branch has no changes.") }, false)
                return 0
            }

            val dateName = LocalDate.now().format(dateFormatter)
            if (branches.containsKey(dateName)) {
                branches[dateName]?.addAll(latestList)
            } else {
                branches[dateName] = latestList.toMutableList()
            }

            branches[latestBranch] = mutableListOf()
            saveBranches()
            ctx.source.sendFeedback({ Text.of("Appended to $dateName and created new latest.") }, false)
            return 1
        }

        private fun getLatest(ctx: CommandContext<ServerCommandSource>): Int {
            return getBranchContent(ctx, latestBranch)
        }

        private fun getBranch(ctx: CommandContext<ServerCommandSource>): Int {
            val branch = StringArgumentType.getString(ctx, "branch")
            return getBranchContent(ctx, branch)
        }

        private fun getBranchContent(ctx: CommandContext<ServerCommandSource>, branch: String): Int {
            val list = branches[branch]
            if (list == null || list.isEmpty()) {
                ctx.source.sendFeedback({ Text.of("No changes for branch: $branch") }, false)
                return 0
            }
            ctx.source.sendFeedback({ Text.of("Changes for $branch:") }, false)
            list.forEach { change ->
                val color = if (change.critical) "§c" else "§7"
                val criticalTag = if (change.critical) "§c[CRITICAL] " else ""
                ctx.source.sendFeedback({ Text.of("$color- [${change.timestamp}] $criticalTag§f${change.text}") }, false)
            }
            return 1
        }

        private fun news(ctx: CommandContext<ServerCommandSource>): Int {
            val player = ctx.source.playerOrThrow
            val uuid = player.uuidAsString
            val lastSeen = playerLastSeen[uuid]
            if (lastSeen == null) {
                ctx.source.sendFeedback({ Text.of("No previous record, use /changes to check") }, false)
                return 0
            }

            val lastSeenTime = java.time.LocalDateTime.parse(lastSeen, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // Find branches with changes newer than last seen
            val branchesWithNewChanges = branches.filter { (_, changeList) ->
                changeList.any { change ->
                    val changeTime = java.time.LocalDateTime.parse(change.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    changeTime.isAfter(lastSeenTime)
                }
            }

            if (branchesWithNewChanges.isEmpty()) {
                ctx.source.sendFeedback({ Text.of("No new changes since last check") }, false)
                return 0
            }

            ctx.source.sendFeedback({ Text.of("Branches with new changes since last check:") }, false)
            branchesWithNewChanges.forEach { (branchName, changes) ->
                val newChangesCount = changes.count { change ->
                    val changeTime = java.time.LocalDateTime.parse(change.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    changeTime.isAfter(lastSeenTime)
                }
                ctx.source.sendFeedback({ Text.of("- $branchName ($newChangesCount new change${if(newChangesCount == 1) "" else "s"})") }, false)
            }

            updatePlayerLastSeen(ctx)
            return 1
        }

        private fun saveBranches() {
            branchesFile.parentFile.mkdirs()
            branchesFile.writeText(gson.toJson(branches))
        }

        fun loadBranches() {
            if (branchesFile.exists()) {
                try {
                    // Try loading as new format (Change objects)
                    val type = object : TypeToken<MutableMap<String, MutableList<Change>>>() {}.type
                    branches = gson.fromJson(branchesFile.readText(), type) ?: ConcurrentHashMap()
                } catch (e: Exception) {
                    // If that fails, try loading as old format (Strings) and migrate
                    try {
                        val oldType = object : TypeToken<MutableMap<String, MutableList<String>>>() {}.type
                        val oldBranches: MutableMap<String, MutableList<String>> =
                            gson.fromJson(branchesFile.readText(), oldType) ?: ConcurrentHashMap()

                        // Migrate to new format
                        branches = ConcurrentHashMap()
                        oldBranches.forEach { (branchName, stringList) ->
                            branches[branchName] = stringList.map { text ->
                                Change(text, "2025-01-01 00:00:00") // Default timestamp for old data
                            }.toMutableList()
                        }

                        // Save in new format
                        saveBranches()
                        println("Migrated changes to new format")
                    } catch (e2: Exception) {
                        println("Failed to load or migrate branches: ${e2.message}")
                        branches[latestBranch] = mutableListOf()
                    }
                }
            } else {
                branchesFile.parentFile.mkdirs()
                branches[latestBranch] = mutableListOf()
                saveBranches()
            }
        }
        private fun loadPlayerData() {
            configFolder.mkdirs()
            if (playerFile.exists()) {
                val type = object : TypeToken<MutableMap<String, String>>() {}.type
                playerLastSeen = gson.fromJson(playerFile.readText(), type) ?: ConcurrentHashMap()
            } else {
                playerFile.writeText(gson.toJson(playerLastSeen))
            }
        }

        private fun savePlayerData() {
            playerFile.writeText(gson.toJson(playerLastSeen))
        }

        private fun updatePlayerLastSeen(ctx: CommandContext<ServerCommandSource>) {
            val source = ctx.source
            if (source.entity == null) return  // console, skip
            val uuid = source.playerOrThrow.uuidAsString
            val now = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            playerLastSeen[uuid] = now
            savePlayerData()
        }

        private val playersWithBackup = mutableSetOf<String>()
    }

    object PlayerSuggestionProvider : SuggestionProvider<ServerCommandSource> {
        override fun getSuggestions(
            context: CommandContext<ServerCommandSource>,
            builder: SuggestionsBuilder
        ): CompletableFuture<Suggestions> {
            // Get all online player names and suggest them
            val playerNames = context.source.server.playerNames
            return CommandSource.suggestMatching(playerNames, builder)
        }
    }
}