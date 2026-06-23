package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /mob コマンドです。
 */
public class MobCommand extends AstCommand {
    private static final List<MobCategory> SPAWNABLE_MOB_CATEGORIES = List.of(MobCategory.ENEMY, MobCategory.BOSS);


    private final MobService mobService;
    private final MobSpawnerService spawnerService;
    private final NpcPlacementService npcPlacementService;

    /**
     * MobCommand を初期化します。
     *
     * @param mobService Mob サービス
     * @param spawnerService Mob スポナーサービス
     */
    public MobCommand(
            @NotNull MobService mobService,
            @NotNull MobSpawnerService spawnerService,
            @NotNull NpcPlacementService npcPlacementService
    ) {
        super("mob", "Manage AstralRecord mobs.", "/mob <load|list|spawn|delete|spawner|npc> [id]",
                true, UserPermission.ADMIN.getValue());
        this.mobService = mobService;
        this.spawnerService = spawnerService;
        this.npcPlacementService = npcPlacementService;
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
            case "npc" -> handleNpc(player, args);
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
        if (!mobService.matchesTemplateCategory(args[1], SPAWNABLE_MOB_CATEGORIES)) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5703.getId(), args[1]));
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
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5719.getId()));
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

    private void handleNpc(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "place" -> handleNpcPlace(player, args);
            case "reload" -> {
                int count = npcPlacementService.loadAll();
                sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5715.getId(), count));
            }
            case "list" -> sendInfo(player.getBukkit(), PlayerMsgResource.format(
                    PlayerMsgId.P_5716.getId(),
                    npcPlacementService.getPlacements().size()
            ));
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleNpcPlace(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!spawnerService.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5719.getId()));
            return;
        }
        if (args.length < 3) {
            sendUsage(player.getBukkit());
            return;
        }
        if (args.length > 3 && args.length < 6) {
            sendUsage(player.getBukkit());
            return;
        }
        if (!mobService.matchesTemplateCategory(args[2], List.of(MobCategory.NPC))) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5703.getId(), args[2]));
            return;
        }

        Location location = player.getBukkit().getLocation();
        if (args.length >= 6) {
            try {
                location = location.clone();
                location.setX(Double.parseDouble(args[3]));
                location.setY(Double.parseDouble(args[4]));
                location.setZ(Double.parseDouble(args[5]));
            } catch (NumberFormatException ex) {
                sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5717.getId()));
                return;
            }
        }

        MobInstance instance = npcPlacementService.place(args[2], location);
        if (instance == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5703.getId(), args[2]));
            return;
        }

        sendSuccess(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5718.getId(),
                instance.template().id(),
                location.getWorld() == null ? "world" : location.getWorld().getName(),
                String.format(Locale.ROOT, "%.2f", location.getX()),
                String.format(Locale.ROOT, "%.2f", location.getY()),
                String.format(Locale.ROOT, "%.2f", location.getZ())
        ));
    }

    private int parseAmount(@NotNull String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }
}
