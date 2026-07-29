package io.github.maaasu.astralRecord.feature.teleporter.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /teleporter 管理コマンドです。
 */
public final class TeleporterCommand extends AstCommand {
    private final TeleporterService teleporterService;

    public TeleporterCommand(@NotNull TeleporterService teleporterService) {
        super("teleporter", "ウェイストーンテレポーターを管理します。", "/teleporter <set|remove|list|reload> ...", true, UserPermission.ADMIN.getValue());
        this.teleporterService = teleporterService;
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
        if (args.length < 4 || args.length > 5) {
            sendUsage(player.getBukkit());
            return;
        }
        String name = args[1].trim();
        if (name.isBlank()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5950.getId()));
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
        if (unlockGold < 0L) {
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
        WaystoneDefinition definition = teleporterService.createWaystone(player, name, lockEnabled, unlockGold, icon);
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5959.getId(), definition.id(), definition.name()));
    }

    private void handleRemove(@NotNull AstPlayer player, @NotNull String[] args) {
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
        int count = teleporterService.reload();
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5958.getId(), count));
    }
}
