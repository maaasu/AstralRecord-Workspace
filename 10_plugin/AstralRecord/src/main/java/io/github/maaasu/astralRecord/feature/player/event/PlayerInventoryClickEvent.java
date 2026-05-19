package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;

public class PlayerInventoryClickEvent extends AbstractEventHandler{

    @EventHandler
    public void onPlayerInventoryClick(InventoryClickEvent e) {
        var player = (Player) e.getWhoClicked();
        var astPlayer = AstPlayerCache.get(player);

        if (astPlayer == null) return;

        var account = astPlayer.getAccount();
        if (account.getMode() == AccountMode.PLAYER || account.getMode() == AccountMode.ADMIN) {
            e.setCancelled(true);
            return;
        }
    }
}
