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

public final class TradeCommand extends AstCommand {

    /**
     * TradeCommand を初期化する。
     */
    public TradeCommand() {
        super("trade", "Request or accept player trade.", "/trade <playerName|accept>", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        TradeService tradeService = AstralRecord.getInstance().getTradeService();
        if (tradeService == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6203);
            return;
        }
        if (args.length == 1 && "accept".equalsIgnoreCase(args[0])) {
            tradeService.acceptTrade(player.getBukkit());
            return;
        }
        if (!checkArgsLength(args, 1, player.getBukkit())) {
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6203);
            return;
        }
        tradeService.requestTrade(player.getBukkit(), target);
    }
}
