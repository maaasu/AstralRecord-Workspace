package io.github.maaasu.astralRecord.feature.guide.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.jetbrains.annotations.NotNull;

/**
 * ガイドGUIを開くコマンドです。
 */
public final class GuideCommand extends AstCommand {

    /**
     * `/guide` コマンドを生成します。
     */
    public GuideCommand() {
        super("guide", "ガイドGUIを開きます。", "/guide", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        AstralRecord plugin = AstralRecord.getInstance();
        MenuView menuView = plugin.getMenuView();
        if (menuView == null) {
            return;
        }
        GuiSound.OPEN.play(player.getBukkit());
        menuView.openGuide(player.getBukkit());
    }
}
