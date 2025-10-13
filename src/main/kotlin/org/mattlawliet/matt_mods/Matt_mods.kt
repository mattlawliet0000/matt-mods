package org.mattlawliet.matt_mods

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class Matt_mods : ModInitializer {

    override fun onInitialize() {
        Companion.loadBranches()
        Companion.loadPlayerData()

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
            val lastSeenTime = java.time.LocalDateTime.parse(lastSeen, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // Check if any branches have changes after their last seen time
            val branchesWithNewChanges = branches.filter { (_, changeList) ->
                changeList.any { change ->
                    java.time.LocalDateTime.parse(change.timestamp, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).isAfter(lastSeenTime)
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
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
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

            val lastSeenTime = java.time.LocalDateTime.parse(lastSeen, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // Find branches with changes newer than last seen
            val branchesWithNewChanges = branches.filter { (_, changeList) ->
                changeList.any { change ->
                    val changeTime = java.time.LocalDateTime.parse(change.timestamp, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
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
                    val changeTime = java.time.LocalDateTime.parse(change.timestamp, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
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
            val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            playerLastSeen[uuid] = now
            savePlayerData()
        }
    }
}
