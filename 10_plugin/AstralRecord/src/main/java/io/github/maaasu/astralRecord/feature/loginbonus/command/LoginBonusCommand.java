package io.github.maaasu.astralRecord.feature.loginbonus.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.jetbrains.annotations.NotNull;

/**
 * ログイン報酬 GUI を開くコマンドです。
 */
public final class LoginBonusCommand extends AstCommand {

    /**
     * /loginbonus コマンドを生成します。
     */
    public LoginBonusCommand() {
        super("loginbonus", "Open login bonus calendar.", "/loginbonus", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        LoginBonusService loginBonusService = AstralRecord.getInstance().getLoginBonusService();
        if (loginBonusService == null) {
            GuiSound.DENY.play(player.getBukkit());
            return;
        }
        GuiSound.OPEN.play(player.getBukkit());
        loginBonusService.openAfterDataLoaded(player.getBukkit());
    }
}
