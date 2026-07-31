package io.github.maaasu.astralRecord.feature.mail.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mail.gui.MailGuiView;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailGuiEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 1. 一覧表示
     * 検証契約: 一覧応答待ちの対象inventoryを閉じた場合、遅延応答でメールGUIを再表示しない。
     */
    @Test
    void closingExpectedScreenCancelsPendingListWithoutReopeningMail() {
        MailGuiView view = mock(MailGuiView.class);
        MailService mailService = mock(MailService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MailGuiEventHandler handler = new MailGuiEventHandler(view, mailService, inventoryService);
        Player player = mock(Player.class);
        InventoryView inventoryView = mock(InventoryView.class);
        Inventory expectedInventory = mock(Inventory.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        UserModel user = mock(UserModel.class);
        AccountModel account = mock(AccountModel.class);
        UUID playerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AtomicReference<Consumer<List<MailEntry>>> completion = new AtomicReference<>();

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(expectedInventory);
        when(astPlayer.getUser()).thenReturn(user);
        when(astPlayer.getAccount()).thenReturn(account);
        when(user.getUuid()).thenReturn(userId);
        when(account.getUuid()).thenReturn(accountId);
        doAnswer(invocation -> {
            completion.set(invocation.getArgument(2));
            return null;
        }).when(mailService).listAsync(any(), any(), any(), any());

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            handler.open(player);

            InventoryCloseEvent closeEvent = mock(InventoryCloseEvent.class);
            when(closeEvent.getPlayer()).thenReturn(player);
            when(closeEvent.getInventory()).thenReturn(expectedInventory);
            handler.onInventoryClose(closeEvent);

            completion.get().accept(List.of());
        }

        verify(view, never()).open(any(), any(), any(), anyInt());
    }
}
