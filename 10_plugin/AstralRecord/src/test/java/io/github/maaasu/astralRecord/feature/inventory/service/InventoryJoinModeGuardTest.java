package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryJoinModeGuardTest {
    /**
     * ADMINへの初期GUI反映はBukkit所持品に触れる前に終了し、所持品を消去しない。
     */
    @Test
    void adminJoinRenderingDoesNotAccessBukkitInventory() {
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.ADMIN);
        InventoryService service = mock(InventoryService.class);
        doCallRealMethod().when(service).applyInventoriesToGuiOnJoin(player);

        service.applyInventoriesToGuiOnJoin(player);

        verify(player, never()).getBukkit();
    }
}
