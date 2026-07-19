package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.service.TrashService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * ゴミ箱GUIを開くコマンド。
 */
public final class TrashCommand extends AstCommand {
    public TrashCommand() {
        super("trash", "ごみ箱GUIを開きます。", "/trash", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        TrashService trashService = AstralRecord.getInstance().getTrashService();
        if (trashService != null) {
            trashService.open(player.getBukkit());
        }
    }
}
