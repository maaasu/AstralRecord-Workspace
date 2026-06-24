package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.jetbrains.annotations.NotNull;

/**
 * メインメニューを開くコマンド。
 */
public final class MenuCommand extends AstCommand {

    /**
     * /menu コマンドを生成します。
     */
    public MenuCommand() {
        super("menu", "Open AstralRecord menu.", "/menu [status]", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!requireGameplayMode(player)) {
            return;
        }
        MenuView menuView = AstralRecord.getInstance().getMenuView();
        if (args.length == 0) {
            GuiSound.OPEN.play(player.getBukkit());
            menuView.open(player.getBukkit());
            return;
        }
        if (args[0].equalsIgnoreCase("status")) {
            StatusSnapshot snapshot = AstralRecord.getInstance().getStatusService().refreshStatus(player);
            GuiSound.OPEN.play(player.getBukkit());
            menuView.openStatus(player.getBukkit(), player, snapshot);
            return;
        }
        sendUsage(player.getBukkit());
    }
}
