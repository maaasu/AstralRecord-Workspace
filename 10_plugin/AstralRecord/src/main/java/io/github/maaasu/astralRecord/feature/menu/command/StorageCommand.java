package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * ストレージ GUI を開くコマンドです。
 */
public final class StorageCommand extends AstCommand {
    public StorageCommand() {
        super("storage", "Open storage GUI.", "/storage", true, UserPermission.ADMIN.getValue());
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        MenuOpenEventHandler handler = AstralRecord.getInstance().getMenuOpenEventHandler();
        if (handler != null) {
            handler.openStorage(player.getBukkit(), 0);
        }
    }
}
