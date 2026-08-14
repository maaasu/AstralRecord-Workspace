package io.github.maaasu.astralRecord.feature.market.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.market.event.MarketGuiEventHandler;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/** 管理者向けのマーケット GUI 起動コマンドです。通常プレイヤーは NPC から利用します。 */
public final class MarketCommand extends AstCommand {
    public MarketCommand() {
        super("market", "マーケットを開きます。", "/market", true, UserPermission.ADMIN.getValue());
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!requireGameplayMode(player)) {
            return;
        }
        MarketGuiEventHandler handler = AstralRecord.getInstance().getMarketGuiEventHandler();
        if (handler == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5063);
            return;
        }
        handler.openFromCommand(player.getBukkit());
    }
}
