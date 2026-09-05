package io.github.maaasu.astralRecord.feature.trade.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class SendCommand extends AstCommand {

    /**
     * SendCommand を初期化する。
     */
    public SendCommand() {
        super("send", "プレイヤーへアイテムやゴールドを送ります。", "/send <playerName>", true);
    }

    /** @param player 実行者 @param args 最後の引数にオンライン受信者名を指定 */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!requireGameplayMode(player)) {
            return;
        }
        TradeService tradeService = AstralRecord.getInstance().getTradeService();
        if (tradeService == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6203);
            return;
        }
        if (args.length != 1) {
            sendUsage(player.getBukkit());
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6203);
            return;
        }
        tradeService.openSend(player.getBukkit(), target, null);
    }
}
