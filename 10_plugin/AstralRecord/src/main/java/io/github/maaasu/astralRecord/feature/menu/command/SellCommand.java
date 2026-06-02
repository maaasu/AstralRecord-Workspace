package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
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
        MenuView menuView = AstralRecord.getInstance().getMenuView();
        menuView.openSell(player.getBukkit(), java.util.List.of(), 0);
    }
}
