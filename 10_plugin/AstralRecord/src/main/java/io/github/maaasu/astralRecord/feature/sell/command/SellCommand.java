package io.github.maaasu.astralRecord.feature.sell.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.sell.service.SellService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * 管理者向けに売却 GUI を開くコマンド。
 */
public final class SellCommand extends AstCommand {
    public SellCommand() {
        super("sell", "Open sell GUI.", "/sell", true, UserPermission.ADMIN.getValue());
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!requireGameplayMode(player)) {
            return;
        }
        SellService sellService = AstralRecord.getInstance().getSellService();
        if (sellService != null) {
            sellService.open(player.getBukkit());
        }
    }
}
