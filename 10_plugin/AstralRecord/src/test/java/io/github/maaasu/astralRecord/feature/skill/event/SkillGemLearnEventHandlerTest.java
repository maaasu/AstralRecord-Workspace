package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSkillGem;
import io.github.maaasu.astralRecord.feature.skill.model.SkillGemLearnConfirmHolder;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
        when(item.getSkillGem()).thenReturn(new ItemSkillGem("mage_fireball"));
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

        bukkitPlayer.closeInventory();
        InventoryClickEvent rightClick = mock(InventoryClickEvent.class);
        when(rightClick.getClick()).thenReturn(ClickType.RIGHT);
        assertTrue(handler.handleInventoryItemClick(rightClick, astPlayer, 3));
        verify(rightClick).setCancelled(true);
        assertNull(bukkitPlayer.getOpenInventory().getTopInventory());
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, UUID> inFlight(SkillGemLearnEventHandler handler)
        throws ReflectiveOperationException {
        Field field = SkillGemLearnEventHandler.class.getDeclaredField("inFlight");
        field.setAccessible(true);
        return (Map<UUID, UUID>) field.get(handler);
    }
}
