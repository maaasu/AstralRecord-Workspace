package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSkillGem;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.skill.model.SkillGemLearnConfirmHolder;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillGemLearnEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルジェム習得
     * 検証契約: 習得API待機中のログアウトは操作トークンを破棄し、再ログイン後をロックしない。
     */
    @Test
    void quitClearsInFlightLearningToken() throws ReflectiveOperationException {
        SkillGemLearnEventHandler handler = new SkillGemLearnEventHandler(
            mock(AstralRecord.class),
            mock(InventoryService.class),
            mock(LearnedSkillService.class),
            new SkillService(mock(SkillRepository.class), new SkillRegistry(), null),
            mock(PassiveSkillService.class)
        );
        var player = server().addPlayer();
        UUID playerId = player.getUniqueId();
        inFlight(handler).put(playerId, UUID.randomUUID());
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        handler.onPlayerQuit(event);

        assertFalse(inFlight(handler).containsKey(playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルジェム習得
     * 検証契約: skill gemは左クリックだけ習得確認を開き、右クリックは消費・画面遷移せず操作を抑止する。
     */
    @Test
    void leftClickOpensLearnConfirmationAndRightClickDoesNothing() {
        var bukkitPlayer = server().addPlayer();
        var astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        InventoryService inventoryService = mock(InventoryService.class);
        InventoryEntryModel entry = mock(InventoryEntryModel.class);
        ItemModel item = mock(ItemModel.class);
        when(entry.getInventoryEntryId()).thenReturn(java.util.UUID.randomUUID());
        when(item.getSkillGem()).thenReturn(new ItemSkillGem("adventurer_smash"));
        when(inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, 3)).thenReturn(entry);
        when(inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, 3)).thenReturn(item);
        SkillService skillService = new SkillService(mock(SkillRepository.class), new SkillRegistry(), null);
        SkillGemLearnEventHandler handler = new SkillGemLearnEventHandler(
            mock(AstralRecord.class),
            inventoryService,
            mock(LearnedSkillService.class),
            skillService,
            mock(PassiveSkillService.class)
        );
        InventoryClickEvent leftClick = mock(InventoryClickEvent.class);
        when(leftClick.getClick()).thenReturn(ClickType.LEFT);

        assertTrue(handler.handleInventoryItemClick(leftClick, astPlayer, 3));
        verify(leftClick).setCancelled(true);
        assertInstanceOf(SkillGemLearnConfirmHolder.class, bukkitPlayer.getOpenInventory().getTopInventory().getHolder());
        assertEquals(
            "スキル習得確認",
            plainText(bukkitPlayer.getOpenInventory().title())
        );
        assertEquals(NamedTextColor.YELLOW, bukkitPlayer.getOpenInventory().title().color());

        bukkitPlayer.closeInventory();
        InventoryClickEvent rightClick = mock(InventoryClickEvent.class);
        when(rightClick.getClick()).thenReturn(ClickType.RIGHT);
        assertTrue(handler.handleInventoryItemClick(rightClick, astPlayer, 3));
        verify(rightClick).setCancelled(true);
        assertNull(bukkitPlayer.getOpenInventory().getTopInventory());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルジェム習得
     * 検証契約: すでに同じスキルを習得済みの場合、警告タイトル・習得済み表示・合成推奨の赤字を表示する。
     */
    @Test
    void duplicateSkillOpensWarningConfirmationGui() {
        var bukkitPlayer = server().addPlayer();
        var astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        InventoryService inventoryService = mock(InventoryService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        InventoryEntryModel entry = mock(InventoryEntryModel.class);
        ItemModel item = mock(ItemModel.class);
        UUID entryId = UUID.randomUUID();
        when(entry.getInventoryEntryId()).thenReturn(entryId);
        when(item.getSkillGem()).thenReturn(new ItemSkillGem("adventurer_smash"));
        when(inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, 3)).thenReturn(entry);
        when(inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, 3)).thenReturn(item);
        when(learnedSkillService.ownsSkill(astPlayer.getAccount().getUuid(), "adventurer_smash"))
            .thenReturn(true);
        SkillGemLearnEventHandler handler = new SkillGemLearnEventHandler(
            mock(AstralRecord.class),
            inventoryService,
            learnedSkillService,
            new SkillService(mock(SkillRepository.class), new SkillRegistry(), null),
            mock(PassiveSkillService.class)
        );
        InventoryClickEvent leftClick = mock(InventoryClickEvent.class);
        when(leftClick.getClick()).thenReturn(ClickType.LEFT);

        assertTrue(handler.handleInventoryItemClick(leftClick, astPlayer, 3));

        Inventory confirmInventory = bukkitPlayer.getOpenInventory().getTopInventory();
        Component title = bukkitPlayer.getOpenInventory().title();
        assertEquals("スキル習得「このスキルの習得を推奨しません」", plainText(title));
        assertEquals(NamedTextColor.RED, title.color());

        var messageLore = Objects.requireNonNull(
            Objects.requireNonNull(confirmInventory.getItem(ConfirmDialogView.MESSAGE_SLOT)).getItemMeta()
        ).lore();
        Component learnedNotice = Objects.requireNonNull(messageLore).stream()
            .filter(line -> plainText(line).equals("このスキルはすでに習得済みです。"))
            .findFirst()
            .orElseThrow();
        assertEquals(NamedTextColor.RED, learnedNotice.color());

        var confirmLore = Objects.requireNonNull(
            Objects.requireNonNull(confirmInventory.getItem(ConfirmDialogView.CONFIRM_SLOT)).getItemMeta()
        ).lore();
        assertTrue(Objects.requireNonNull(confirmLore).stream()
            .filter(line -> plainText(line).contains("合成に使用することをお勧めします。"))
            .peek(line -> assertEquals(NamedTextColor.RED, line.color()))
            .findAny()
            .isPresent());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルジェム習得
     * 検証契約: 習得 mutation の失敗後、API 正本へ再同期された所持品を確認 GUI 下部へ再描画する。
     */
    @Test
    @SuppressWarnings("unchecked")
    void learningFailureRestoresManagedInventoryInConfirmationGui() {
        AstralRecord plugin = mock(AstralRecord.class);
        InventoryService inventoryService = mock(InventoryService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        SkillGemLearnEventHandler handler = new SkillGemLearnEventHandler(
            plugin,
            inventoryService,
            learnedSkillService,
            new SkillService(mock(SkillRepository.class), new SkillRegistry(), null),
            mock(PassiveSkillService.class)
        );
        var player = mock(org.bukkit.entity.Player.class);
        var astPlayer = mock(AstPlayer.class);
        var account = mock(AccountModel.class);
        var event = mock(InventoryClickEvent.class);
        var view = mock(InventoryView.class);
        var topInventory = mock(Inventory.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        UUID accountId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        AtomicReference<Consumer<Throwable>> failure = new AtomicReference<>();

        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("tester");
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(event.getRawSlot()).thenReturn(ConfirmDialogView.CONFIRM_SLOT);
        when(view.getTopInventory()).thenReturn(topInventory);
        when(topInventory.getSize()).thenReturn(27);
        when(topInventory.getHolder()).thenReturn(new SkillGemLearnConfirmHolder(entryId, "adventurer_smash"));
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(learnedSkillService.learnAsync(
            eq(accountId), eq("adventurer_smash"), eq(entryId), eq(accountId), any(Consumer.class), any(Consumer.class)
        )).thenAnswer(invocation -> {
            failure.set(invocation.getArgument(5));
            return true;
        });

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            handler.onConfirmClick(event);
            failure.get().accept(new IllegalStateException("api response interrupted"));
        }

        verify(inventoryService).applyInventoriesToGui(astPlayer);
        verify(player).updateInventory();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, UUID> inFlight(SkillGemLearnEventHandler handler)
        throws ReflectiveOperationException {
        Field field = SkillGemLearnEventHandler.class.getDeclaredField("inFlight");
        field.setAccessible(true);
        return (Map<UUID, UUID>) field.get(handler);
    }

    private static String plainText(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
