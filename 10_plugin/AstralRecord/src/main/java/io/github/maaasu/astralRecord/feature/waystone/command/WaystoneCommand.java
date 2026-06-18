package io.github.maaasu.astralRecord.feature.waystone.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.waystone.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.waystone.service.WaystoneService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * /waystone コマンドです。
 */
public final class WaystoneCommand extends AstCommand {
    private static final int ADMIN_PERMISSION = 99;
    private final WaystoneService service;

    /**
     * コマンドを初期化します。
     *
     * @param service ウェイストーンサービス
     */
    public WaystoneCommand(@NotNull WaystoneService service) {
        super("waystone", "Create a waystone.", "/waystone <name> [alwaysUnlocked] [unlockGoldCost]", true, ADMIN_PERMISSION);
        this.service = service;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 1) {
            sendUsage(player.getBukkit());
            return;
        }
        String name = args[0];
        boolean alwaysUnlocked = args.length >= 2 && ("1".equals(args[1]) || Boolean.parseBoolean(args[1]));
        long goldCost = args.length >= 3 ? parseLong(args[2], 100L) : 100L;
        try {
            WaystoneDefinition definition = service.create(name, player.getBukkit().getLocation(), alwaysUnlocked, goldCost);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6500, definition.name(), definition.id());
        } catch (IOException e) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6507);
        }
    }

    private long parseLong(@NotNull String value, long fallback) {
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
