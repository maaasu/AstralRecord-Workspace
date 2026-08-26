package io.github.maaasu.astralRecord.feature.mail.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mail.gui.MailGuiView;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.feature.mail.model.MailReward;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
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
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_2-ユースケース.md
     * 章・見出し: # 18_2-ユースケース > ## UC-18-03 メールを読む／報酬を受け取る
     * 検証契約: 受け取り済みのメールをクリックして報酬を付与しない成功時、ダミーアイテムの通常クリックと同じ SELECT 音を一度だけ再生する。
     */
    @Test
    void readMailClickPlaysSelectSound() {
        MailGuiView view = mock(MailGuiView.class);
        MailService mailService = mock(MailService.class);
        MailGuiEventHandler handler = new MailGuiEventHandler(view, mailService, mock(InventoryService.class));
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView inventoryView = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        ItemStack itemStack = mock(ItemStack.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        UserModel user = mock(UserModel.class);
        AccountModel account = mock(AccountModel.class);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        MailEntry mail = new MailEntry(
            "mail-1",
            "CHEST",
            "受け取り済みメール",
            "本文",
            java.time.LocalDateTime.now().minusMinutes(1),
            null,
            true,
            List.of(),
            true,
            null
        );
        AtomicReference<Consumer<MailService.ReadAndReceiveResult>> completion = new AtomicReference<>();

        when(event.getView()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getRawSlot()).thenReturn(0);
        when(event.getCurrentItem()).thenReturn(itemStack);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(view.isInventory(inventory)).thenReturn(true);
        when(view.getFilter(inventory)).thenReturn(MailFilter.ALL);
        when(view.getPageIndex(inventory)).thenReturn(0);
        when(view.getMails(inventory)).thenReturn(List.of(mail));
        when(view.getMailId(itemStack)).thenReturn(mail.id());
        when(astPlayer.getUser()).thenReturn(user);
        when(astPlayer.getAccount()).thenReturn(account);
        when(user.getUuid()).thenReturn(userId);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getLocation()).thenReturn(location);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(player.isOnline()).thenReturn(true);
        doAnswer(invocation -> {
            completion.set(invocation.getArgument(2));
            return null;
        }).when(mailService).readAndReceive(any(), any(), any());

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            handler.onInventoryClick(event);
            completion.get().accept(new MailService.ReadAndReceiveResult(true, false));
        }

        verify(player).playSound(location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.6F, 1.4F);
        verify(player, never()).playSound(
            location,
            Sound.UI_TOAST_CHALLENGE_COMPLETE,
            SoundCategory.PLAYERS,
            0.7F,
            1.0F
        );
        verify(player, never()).playSound(
            location,
            Sound.ENTITY_PLAYER_LEVELUP,
            SoundCategory.PLAYERS,
            0.65F,
            1.15F
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_2-ユースケース.md
     * 章・見出し: # 18_2-ユースケース > ## UC-18-03 メールを読む／報酬を受け取る
     * 検証契約: メール報酬が inventory に残る成功時、豪華な報酬音ではなく ITEM_RECEIVE 音を一度だけ再生する。
     */
    @Test
    void rewardReceiptPlaysItemReceiveSound() {
        MailGuiView view = mock(MailGuiView.class);
        MailService mailService = mock(MailService.class);
        MailGuiEventHandler handler = new MailGuiEventHandler(view, mailService, mock(InventoryService.class));
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView inventoryView = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        ItemStack itemStack = mock(ItemStack.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        UserModel user = mock(UserModel.class);
        AccountModel account = mock(AccountModel.class);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        MailEntry mail = new MailEntry(
            "mail-1",
            "CHEST",
            "報酬メール",
            "本文",
            java.time.LocalDateTime.now().minusMinutes(1),
            null,
            true,
            List.of(new MailReward("reward-item", "material", 1)),
            false,
            null
        );
        AtomicReference<Consumer<MailService.ReadAndReceiveResult>> completion = new AtomicReference<>();

        when(event.getView()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getRawSlot()).thenReturn(0);
        when(event.getCurrentItem()).thenReturn(itemStack);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(view.isInventory(inventory)).thenReturn(true);
        when(view.getFilter(inventory)).thenReturn(MailFilter.ALL);
        when(view.getPageIndex(inventory)).thenReturn(0);
        when(view.getMails(inventory)).thenReturn(List.of(mail));
        when(view.getMailId(itemStack)).thenReturn(mail.id());
        when(astPlayer.getUser()).thenReturn(user);
        when(astPlayer.getAccount()).thenReturn(account);
        when(user.getUuid()).thenReturn(userId);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getLocation()).thenReturn(location);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(player.isOnline()).thenReturn(true);
        doAnswer(invocation -> {
            completion.set(invocation.getArgument(2));
            return null;
        }).when(mailService).readAndReceive(any(), any(), any());

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            handler.onInventoryClick(event);
            completion.get().accept(new MailService.ReadAndReceiveResult(true, true));
        }

        verify(player).playSound(location, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.45F, 1.35F);
        verify(player, never()).playSound(
            location,
            Sound.UI_TOAST_CHALLENGE_COMPLETE,
            SoundCategory.PLAYERS,
            0.7F,
            1.0F
        );
        verify(player, never()).playSound(
            location,
            Sound.ENTITY_PLAYER_LEVELUP,
            SoundCategory.PLAYERS,
            0.65F,
            1.15F
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 1. 一覧表示
     * 検証契約: 一覧の有効な前ページ操作はページ移動音を一度だけ再生して再描画する。
     */
    @Test
    void previousPagePlaysPageSound() {
        MailGuiView view = mock(MailGuiView.class);
        MailGuiEventHandler handler = new MailGuiEventHandler(view, mock(MailService.class), mock(InventoryService.class));
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView inventoryView = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        List<MailEntry> mails = List.of();

        when(event.getView()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getRawSlot()).thenReturn(MailGuiView.PREVIOUS_SLOT);
        when(view.isInventory(inventory)).thenReturn(true);
        when(view.getFilter(inventory)).thenReturn(MailFilter.ALL);
        when(view.getPageIndex(inventory)).thenReturn(1);
        when(view.getMails(inventory)).thenReturn(mails);
        when(view.hasPreviousPage(1)).thenReturn(true);
        when(player.getLocation()).thenReturn(location);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getOpenInventory()).thenReturn(inventoryView);

        handler.onInventoryClick(event);

        verify(player).playSound(location, Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.55F, 1.3F);
        verify(view).open(player, mails, MailFilter.ALL, 0);
    }

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
