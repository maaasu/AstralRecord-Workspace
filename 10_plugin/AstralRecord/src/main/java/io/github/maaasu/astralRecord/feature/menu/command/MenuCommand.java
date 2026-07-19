package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
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
        super("menu", "AstralRecord メニューを開きます。", "/menu [status|guide|mail]", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!requireGameplayMode(player)) {
            return;
        }
        AstralRecord plugin = AstralRecord.getInstance();
        MenuView menuView = plugin.getMenuView();
        if (args.length == 0) {
            GuiSound.OPEN.play(player.getBukkit());
            menuView.open(player, plugin.getPlayerGuiRenderContextFactory().create(player));
            return;
        }
        if (args[0].equalsIgnoreCase("status")) {
            PlayerBrowserGuiEventHandler handler = AstralRecord.getInstance().getPlayerBrowserGuiEventHandler();
            if (handler == null) {
                return;
            }
            GuiSound.OPEN.play(player.getBukkit());
            handler.openSelfDetail(player.getBukkit());
            return;
        }
        if (args[0].equalsIgnoreCase("guide")) {
            GuiSound.OPEN.play(player.getBukkit());
            menuView.openGuide(player.getBukkit());
            return;
        }
        if (args[0].equalsIgnoreCase("mail")) {
            var mailGuiEventHandler = AstralRecord.getInstance().getMailGuiEventHandler();
            if (mailGuiEventHandler != null) {
                GuiSound.OPEN.play(player.getBukkit());
                mailGuiEventHandler.open(player.getBukkit());
                return;
            }
        }
        sendUsage(player.getBukkit());
    }
}
