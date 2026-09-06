package io.github.maaasu.astralRecord.feature.world.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.world.service.AdminWorldTeleportItemService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class AdminWorldTeleportItemCommandTest extends MockBukkitTestBase {
    private Player cachedPlayer;

    @AfterEach
    void clearCachedPlayer() {
        if (cachedPlayer != null) {
            AstPlayerCache.remove(cachedPlayer.getUniqueId());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-コマンド.md
     * 章・見出し: # 17_3-コマンド > ## `/adminitem`
     * 検証契約: 管理者権限を持つプレイヤーが /adminitem を実行すると、専用アイテムをインベントリへ1個取得する。
     */
    @Test
    void adminPlayerReceivesTeleportItem() {
        cachedPlayer = server().addPlayer("Admin");
        AstPlayer astPlayer = Mockito.mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(cachedPlayer);
        when(astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())).thenReturn(true);
        AstPlayerCache.put(astPlayer);

        new AdminWorldTeleportItemCommand(new AdminWorldTeleportItemService())
                .onCommand(cachedPlayer, null, "adminitem", new String[0]);

        var service = new AdminWorldTeleportItemService();
        assertTrue(service.isTeleportItem(cachedPlayer.getInventory().getItem(0)));
    }
}
