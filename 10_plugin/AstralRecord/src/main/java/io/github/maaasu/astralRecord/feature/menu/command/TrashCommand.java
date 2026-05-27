package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * ゴミ箱GUIを開くコマンド。
 */
public final class TrashCommand extends AstCommand {
    public TrashCommand() {
        super("trash", "Open trash GUI.", "/trash", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        MenuView menuView = AstralRecord.getInstance().getMenuView();
        menuView.openTrash(player.getBukkit(), java.util.List.of(), 0);
    }
}
