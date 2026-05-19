package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * メインメニューを開くコマンド。
 */
public final class MenuCommand extends AstCommand {

    /**
     * /menu コマンドを生成します。
     */
    public MenuCommand() {
        super("menu", "Open AstralRecord menu.", "/menu", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        AstralRecord.getInstance().getMenuView().open(player.getBukkit());
    }
}
