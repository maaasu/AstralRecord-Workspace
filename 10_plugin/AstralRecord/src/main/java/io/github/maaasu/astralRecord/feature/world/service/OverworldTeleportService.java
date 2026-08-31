package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 拠点ワールドからオーバーワールドへ移動する GUI 向けのワールド選択と転送を扱います。
 */
public final class OverworldTeleportService {
    private final Plugin plugin;
    private final WorldService worldService;
    private final InventoryService inventoryService;

    /**
     * サービスを初期化します。
     *
     * @param plugin プラグイン本体
     * @param worldService ワールドサービス
     * @param inventoryService 通貨所持数を確認するインベントリサービス
     */
    public OverworldTeleportService(
            @NotNull Plugin plugin,
            @NotNull WorldService worldService,
            @NotNull InventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.inventoryService = inventoryService;
    }

    /**
     * GUI に表示するオーバーワールド一覧を返します。
     *
     * @return 表示順に整列した `WorldMasterData`
     */
    public @NotNull List<WorldMasterData> listDestinations() {
        Map<Integer, WorldMasterData> destinationBySlot = new TreeMap<>();
        worldService.getAll().stream()
                .filter(world -> world.worldType() == WorldType.OVERWORLD)
                .filter(world -> world.overworldTeleportGui() != null
                        && world.overworldTeleportGui().hasValidSlot())
                .sorted(Comparator.comparing(WorldMasterData::id))
                .forEach(world -> destinationBySlot.putIfAbsent(world.overworldTeleportGui().slot(), world));
        return List.copyOf(destinationBySlot.values());
    }

    /**
     * 現在ワールドが拠点ワールドかを返します。
     *
     * @param world Bukkit ワールド
     * @return 拠点ワールドなら {@code true}
     */
    public boolean isBaseWorld(@Nullable World world) {
        if (world == null) {
            return false;
        }
        WorldMasterData data = worldService.findByBukkitWorld(world);
        return data != null && data.worldType() == WorldType.BASE;
    }

    /**
     * 指定ワールド ID のオーバーワールドへ転送します。
     *
     * @param player 対象プレイヤー
     * @param astPlayer AstralRecord プレイヤー
     * @param worldId 転送先ワールド ID
     */
    public void teleportToDestination(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull String worldId
    ) {
        WorldMasterData destination = worldService.getById(worldId);
        if (destination == null || destination.worldType() != WorldType.OVERWORLD) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5766, worldId, worldId);
            return;
        }

        if (!hasRequiredItem(astPlayer, destination)) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5777);
            return;
        }

        String displayName = ColorCodeUtil.toPlainText(destination.displayName(), destination.id());
        player.closeInventory();
        worldService.teleportToSpawnAsync(player, destination, null,
                () -> hasRequiredItem(astPlayer, destination)).whenComplete((success, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null || !Boolean.TRUE.equals(success)) {
                        if (!hasRequiredItem(astPlayer, destination)) {
                            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5777);
                        } else {
                            PlayerMessageService.getInstance().send(
                                    astPlayer,
                                    PlayerMsgId.P_5766,
                                    displayName,
                                    destination.id()
                            );
                        }
                    }
                })
        );
    }

    private boolean hasRequiredItem(@NotNull AstPlayer astPlayer, @NotNull WorldMasterData destination) {
        String requiredItemId = destination.requiredItemId();
        if (requiredItemId == null) {
            return true;
        }
        if (requiredItemId.isBlank()) {
            return false;
        }
        return inventoryService.getCurrencyAmount(
                astPlayer.getAccount().getUuid(),
                requiredItemId
        ) >= 1L;
    }
}
