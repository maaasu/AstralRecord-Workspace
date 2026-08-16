package io.github.maaasu.astralRecord.feature.currency.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * 通貨GUIを開くコマンドです。
 */
public final class CurrencyCommand extends AstCommand {

    /**
     * `/currency` コマンドを生成します。
     */
    public CurrencyCommand() {
        super("currency", "通貨GUIを開きます。", "/currency", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        MenuOpenEventHandler menuOpenEventHandler = AstralRecord.getInstance().getMenuOpenEventHandler();
        if (menuOpenEventHandler != null) {
            menuOpenEventHandler.openCurrencyFromCommand(player.getBukkit());
        }
    }
}
