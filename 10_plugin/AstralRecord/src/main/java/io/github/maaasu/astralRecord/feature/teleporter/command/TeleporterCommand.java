package io.github.maaasu.astralRecord.feature.teleporter.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.worldplacement.repository.WorldPlacementRepository;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * /teleporter 管理コマンドです。
 */
public final class TeleporterCommand extends AstCommand {
    private static final long MAX_EDITOR_UNLOCK_GOLD = 9_007_199_254_740_991L;

    private final AstralRecord plugin;
    private final TeleporterService teleporterService;
    private final WorldPlacementRepository worldPlacementRepository;

    /**
     * @param plugin 配置の再読込とタスクの所有者
     * @param teleporterService ウェイストーン定義の管理サービス
     * @param worldPlacementRepository Dev 配置 API のリポジトリ
     */
    public TeleporterCommand(@NotNull AstralRecord plugin, @NotNull TeleporterService teleporterService,
            @NotNull WorldPlacementRepository worldPlacementRepository) {
        super("teleporter", "ウェイストーンテレポーターを管理します。", "/teleporter <set|remove|list|reload> ...", true, UserPermission.ADMIN.getValue());
        this.plugin = plugin;
        this.teleporterService = teleporterService;
        this.worldPlacementRepository = worldPlacementRepository;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> handleSet(player, args);
            case "remove" -> handleRemove(player, args);
            case "list" -> handleList(player);
            case "reload" -> handleReload(player);
            default -> sendUsage(player.getBukkit());
        }
    }

    /**
     * ウェイストーンの作成引数を検証し、省略可能なアイコンを含めて登録します。
     *
     * @param player 実行者
     * @param args set サブコマンドを含む引数
     */
    private void handleSet(@NotNull AstPlayer player, @NotNull String[] args) {
        boolean apiMode = worldPlacementRepository.isApiMode();
        if (apiMode && !worldPlacementRepository.isDevApiMode()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_6410.getId()));
            return;
        }
        if (apiMode && !worldPlacementRepository.hasWriteKey()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_6416.getId()));
            return;
        }
        if (args.length < 4 || args.length > 5) {
            sendUsage(player.getBukkit());
            return;
        }
        String name = args[1].trim();
        if (name.isBlank()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5950.getId()));
            return;
        }
        if (apiMode && name.length() > 128) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5951.getId()));
            return;
        }
        boolean lockEnabled;
        if ("true".equalsIgnoreCase(args[2]) || "false".equalsIgnoreCase(args[2])) {
            lockEnabled = Boolean.parseBoolean(args[2]);
        } else {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5951.getId()));
            return;
        }
        long unlockGold;
        try {
            unlockGold = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5951.getId()));
            return;
        }
        if (unlockGold < 0L || apiMode && lockEnabled && unlockGold > MAX_EDITOR_UNLOCK_GOLD) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5951.getId()));
            return;
        }
        Material icon = null;
        if (args.length == 5) {
            icon = Material.matchMaterial(args[4]);
            if (icon == null || !icon.isItem() || icon.isAir()) {
                sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5951.getId()));
                return;
            }
        }
        if (apiMode) {
            registerDevWaystone(player, name, lockEnabled, unlockGold, icon);
            return;
        }
        WaystoneDefinition definition = teleporterService.createWaystone(player, name, lockEnabled, unlockGold, icon);
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5959.getId(), definition.id(), definition.name()));
    }

    /**
     * 現在地を取得し、非同期で Dev に登録します。保存確認後に最新の配置を公開します。
     *
     * @param player 作成者
     * @param name 表示名
     * @param lockEnabled ロック有効フラグ
     * @param unlockGold 解除に必要なゴールド
     * @param icon 表示アイコン。未指定の場合は null
     */
    private void registerDevWaystone(AstPlayer player, String name, boolean lockEnabled, long unlockGold,
            @Nullable Material icon) {
        var location = player.getBukkit().getLocation();
        var world = location.getWorld();
        var worldService = plugin.getWorldService();
        var worldData = worldService.findByBukkitWorld(world);
        if (worldData == null || worldData.worldType() == WorldType.TEMP
                || !world.equals(worldService.resolveLoadedWorld(worldData))) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_6417.getId()));
            return;
        }
        var definition = teleporterService.captureDevWaystone(player, location, name, lockEnabled, unlockGold, icon);
        UUID playerId = player.getBukkit().getUniqueId();
        sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_6421.getId()));
        AsyncTaskUtil.supplyAsync(plugin, () -> worldPlacementRepository.createDevWaystone(worldData.id(), definition))
            .whenComplete((saved, failure) -> {
                if (failure != null) {
                    Logger.log(LogId.E_6412, failure, definition.id());
                    notifyRegistration(playerId, PlayerMsgId.P_6419, definition.id());
                } else if (!saved) {
                    notifyRegistration(playerId, PlayerMsgId.P_6418);
                } else {
                    Logger.log(LogId.I_5950, definition.id(), definition.name(), definition.worldName());
                    plugin.reloadWorldPlacementsAfterWrite().whenComplete((snapshot, reloadFailure) -> {
                        if (reloadFailure != null || snapshot.waystones().stream().noneMatch(row -> row.id().equals(definition.id()))) {
                            notifyRegistration(playerId, PlayerMsgId.P_6420, definition.id());
                        } else {
                            notifyRegistration(playerId, PlayerMsgId.P_5959, definition.id(), definition.name());
                        }
                    });
                }
            });
    }

    /** ログイン中の実行者へメインスレッドから結果を通知します。 */
    private void notifyRegistration(UUID playerId, PlayerMsgId messageId, Object... args) {
        if (plugin.isEnabled()) {
            AsyncTaskUtil.runSync(plugin, () -> {
                var player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    PlayerMessageService.getInstance().send(player, messageId, args);
                }
            });
        }
    }

    private void handleRemove(@NotNull AstPlayer player, @NotNull String[] args) {
        if (teleporterService.isPlacementReadOnly()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_6410.getId()));
            return;
        }
        if (args.length != 2) {
            sendUsage(player.getBukkit());
            return;
        }
        if (!teleporterService.removeWaystone(args[1])) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5960.getId(), args[1]));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5959.getId(), args[1], "removed"));
    }

    /**
     * 登録済みウェイストーンとフォールバック解決後のアイコンを一覧表示します。
     *
     * @param player 実行者
     */
    private void handleList(@NotNull AstPlayer player) {
        var definitions = teleporterService.getAll();
        sendInfo(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5958.getId(), definitions.size()));
        for (WaystoneDefinition definition : definitions) {
            sendInfo(player.getBukkit(), PlayerMsgResource.format(
                    PlayerMsgId.P_5961.getId(),
                    definition.id(),
                    definition.name(),
                    definition.worldName(),
                    definition.lockEnabled(),
                    definition.unlockGold(),
                    definition.displayIcon().name()
            ));
        }
    }

    private void handleReload(@NotNull AstPlayer player) {
        if (teleporterService.isPlacementReadOnly()) {
            sendWarning(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_6415.getId()));
            return;
        }
        int count = teleporterService.reload();
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5958.getId(), count));
    }
}
