package io.github.maaasu.astralRecord.feature.shop.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

public final class ShopCommand extends AstCommand {
    public ShopCommand() {
        super("shop", "Open a shop.", "/shop <shopId>", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 1) {
            sendUsage(player.getBukkit());
            return;
        }
        var plugin = AstralRecord.getInstance();
        var shopService = plugin.getShopService();
        var shopGuiEventHandler = plugin.getShopGuiEventHandler();
        if (shopService == null || shopGuiEventHandler == null) {
            player.sendMessage(PlayerMsgId.P_5063);
            return;
        }
        String shopInput = joinArgs(args, 0);
        var shop = shopService.findByIdOrName(shopInput);
        if (shop == null) {
            player.sendMessage(PlayerMsgId.P_5930, shopInput);
            return;
        }
        shopGuiEventHandler.open(player.getBukkit(), shop.id());
    }
}
