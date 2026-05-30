package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /mob コマンドです。
 */
public class MobCommand extends AstCommand {

    private final MobService mobService;
    private final MobSpawnerService spawnerService;

    /**
     * MobCommand を初期化します。
     *
     * @param mobService Mob サービス
     * @param spawnerService Mob スポナーサービス
     */
    public MobCommand(@NotNull MobService mobService, @NotNull MobSpawnerService spawnerService) {
        super("mob", "Manage AstralRecord mobs.", "/mob <load|list|spawn|delete|spawner> [id]", true);
        this.mobService = mobService;
        this.spawnerService = spawnerService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "load" -> handleLoad(player);
            case "list" -> handleList(player);
            case "spawn" -> handleSpawn(player, args);
            case "delete" -> handleDelete(player, args);
            case "spawner" -> handleSpawner(player, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleLoad(@NotNull AstPlayer player) {
        int count = mobService.loadAll();
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5700.getId(), count));
    }

    private void handleList(@NotNull AstPlayer player) {
        var ids = mobService.getLoadedMobIds();
        if (ids.isEmpty()) {
            sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5701.getId()));
            return;
        }
        sendInfo(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5702.getId(), String.join(", ", ids)));
    }

    private void handleSpawn(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        MobInstance instance = mobService.spawn(args[1], player.getBukkit().getLocation());
        if (instance == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5703.getId(), args[1]));
            return;
        }

        sendSuccess(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5704.getId(),
                instance.template().id(),
                instance.instanceId()
        ));
    }

    private void handleDelete(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        int count = mobService.destroyById(args[1]);
        if (count <= 0) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5705.getId(), args[1]));
            return;
        }

        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5706.getId(), count));
    }

    private void handleSpawner(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                int count = spawnerService.loadAll();
                sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5712.getId(), count));
            }
            case "list" -> sendInfo(player.getBukkit(), PlayerMsgResource.format(
                    PlayerMsgId.P_5713.getId(),
                    String.join(", ", spawnerService.getLoadedSpawnerIds()),
                    spawnerService.getLocations().size()
            ));
            case "item" -> handleSpawnerItem(player, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleSpawnerItem(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!spawnerService.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }
        if (args.length < 3) {
            sendUsage(player.getBukkit());
            return;
        }

        int amount = args.length >= 4 ? parseAmount(args[3]) : 1;
        ItemStack itemStack = spawnerService.createSpawnerItem(args[2], amount);
        if (itemStack == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5708.getId(), args[2]));
            return;
        }

        var leftover = player.getBukkit().getInventory().addItem(itemStack);
        if (!leftover.isEmpty()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5241.getId()));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5714.getId(), args[2], amount));
    }

    private int parseAmount(@NotNull String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }
}
