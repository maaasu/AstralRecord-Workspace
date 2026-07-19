package io.github.maaasu.astralRecord.feature.storage.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.storage.service.StorageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * ストレージ GUI を開くコマンドです。
 */
public final class StorageCommand extends AstCommand {
    public StorageCommand() {
        super("storage", "倉庫GUIを開きます。", "/storage", true, UserPermission.ADMIN.getValue());
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!requireGameplayMode(player)) {
            return;
        }
        StorageService storageService = AstralRecord.getInstance().getStorageService();
        if (storageService != null) {
            storageService.open(player.getBukkit());
        }
    }
}
