package io.github.maaasu.astralRecord.feature.world.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * /world コマンドです。
 */
public class WorldCommand extends AstCommand {

    private final WorldService worldService;

    /**
     * WorldCommand を初期化します。
     *
     * @param worldService WorldMasterData サービス
     */
    public WorldCommand(@NotNull WorldService worldService) {
        super("world", "Manage world master data.", "/world <list|info|tp|loaded|reload> [worldId]",
                true, UserPermission.ADMIN.getValue());
        this.worldService = worldService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> handleList(player);
            case "info" -> handleInfo(player, args);
            case "tp" -> handleTeleport(player, args);
            case "loaded" -> handleLoaded(player);
            case "reload" -> handleReload(player);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleList(@NotNull AstPlayer player) {
        var worlds = worldService.getAll();
        if (worlds.isEmpty()) {
            sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5751.getId()));
            return;
        }

        sendInfo(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5752.getId(), worlds.size()));
        for (WorldMasterData world : worlds) {
            sendInfo(player.getBukkit(), PlayerMsgResource.format(
                    PlayerMsgId.P_5753.getId(),
                    world.id(),
                    world.displayName(),
                    world.worldType().name(),
                    world.instanceEnabled()
            ));
        }
    }

    private void handleInfo(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }

        WorldMasterData world = worldService.getById(args[1]);
        if (world == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5754.getId(), args[1]));
            return;
        }

        sendInfo(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5755.getId(),
                world.id(),
                world.displayName()
        ));
        sendInfo(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5756.getId(),
                world.worldType().name(),
                world.baseWorldPath(),
                world.instanceRootPath()
        ));
        sendInfo(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5757.getId(),
                world.autoLoad(),
                world.instanceEnabled(),
                world.maxPlayers()
        ));
        sendInfo(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5758.getId(),
                world.allowBlockBreak(),
                world.allowBlockPlace(),
                world.allowMobSpawn()
        ));
        sendInfo(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5759.getId(), world.description()));
    }

    private void handleTeleport(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }

        WorldMasterData data = worldService.getById(args[1]);
        if (data == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5754.getId(), args[1]));
            return;
        }

        var spawnLocation = worldService.resolveSpawnLocation(data);
        if (spawnLocation == null || spawnLocation.getWorld() == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5760.getId(), data.id()));
            return;
        }

        player.getBukkit().teleport(spawnLocation);
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5761.getId(), spawnLocation.getWorld().getName()));
    }

    private void handleLoaded(@NotNull AstPlayer player) {
        String loaded = Bukkit.getWorlds().stream()
                .map(org.bukkit.World::getName)
                .collect(Collectors.joining(", "));
        if (loaded.isBlank()) {
            loaded = "-";
        }
        sendInfo(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5762.getId(), loaded));
    }

    private void handleReload(@NotNull AstPlayer player) {
        int count = worldService.reloadFromYaml();
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5763.getId(), count));
    }
}
