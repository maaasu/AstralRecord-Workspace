package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingHoldService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillActionRingEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-統合フロー.md
     * 章・見出し: # 13_4-統合フロー > ## 4. action ring・skilltree 入力調停
     * 検証契約: 長押し設定中でも2件以上かつ空中右クリックだけを非cancelのCLAIMで受ける。
     */
    @Test
    void holdSelectionClaimsOnlyAirRightClickWithMultipleActions() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);
        SkillActionRingService actionRingService = mock(SkillActionRingService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        SkillActionRingHoldService holdService = mock(SkillActionRingHoldService.class);
        when(inventoryService.getItemModelInHand(astPlayer, EquipmentSlot.HAND)).thenReturn(
            DesignTestFixtures.equipmentItem("test_weapon", "status:attack", ItemEquipmentStatType.FLAT)
        );
        when(playerSettingService.isActionRingHoldSelectEnabled(player.getUniqueId())).thenReturn(true);
        when(actionRingService.hasMultipleConfiguredActions(astPlayer)).thenReturn(true);
        SkillActionRingEventHandler handler = new SkillActionRingEventHandler(
            actionRingService, inventoryService, playerSettingService, holdService
        );
        try {
            Collection<PlayerInputCandidate> airCandidates = handler.resolve(context(player, Action.RIGHT_CLICK_AIR));
            assertEquals(1, airCandidates.size());
            PlayerInputCandidate airCandidate = airCandidates.iterator().next();
            assertEquals(InputClaimPolicy.CLAIM, airCandidate.claimPolicy());
            assertTrue(airCandidate.executeIfValid());
            verify(holdService).begin(astPlayer);

            assertTrue(handler.resolve(context(player, Action.RIGHT_CLICK_BLOCK)).isEmpty());
            verify(actionRingService, never()).toggle(astPlayer);
        } finally {
            AstPlayerCache.remove(player.getUniqueId());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-統合フロー.md
     * 章・見出し: # 13_4-統合フロー > ## 4. action ring・skilltree 入力調停
     * 検証契約: 設定済みactionが1件以下なら長押し設定を無効扱いにして通常右クリックをcancelする。
     */
    @Test
    void singleActionUsesNormalCancelledRightClick() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);
        SkillActionRingService actionRingService = mock(SkillActionRingService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        SkillActionRingHoldService holdService = mock(SkillActionRingHoldService.class);
        when(inventoryService.getItemModelInHand(astPlayer, EquipmentSlot.HAND)).thenReturn(
            DesignTestFixtures.equipmentItem("test_weapon", "status:attack", ItemEquipmentStatType.FLAT)
        );
        when(playerSettingService.isActionRingHoldSelectEnabled(player.getUniqueId())).thenReturn(true);
        when(actionRingService.hasMultipleConfiguredActions(astPlayer)).thenReturn(false);
        SkillActionRingEventHandler handler = new SkillActionRingEventHandler(
            actionRingService, inventoryService, playerSettingService, holdService
        );
        try {
            Collection<PlayerInputCandidate> candidates = handler.resolve(context(player, Action.RIGHT_CLICK_BLOCK));
            assertEquals(1, candidates.size());
            PlayerInputCandidate candidate = candidates.iterator().next();
            assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidate.claimPolicy());
            assertTrue(candidate.executeIfValid());
            verify(actionRingService).toggle(astPlayer);
            verify(holdService, never()).begin(astPlayer);
        } finally {
            AstPlayerCache.remove(player.getUniqueId());
        }
    }

    private PlayerInputContext<PlayerInteractionSnapshot> context(PlayerMock player, Action action) {
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
            new Vector(0.0D, 64.0D, 0.0D),
            new Vector(0.0D, 0.0D, 1.0D),
            8.0D
        );
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            player,
            mock(Event.class),
            EquipmentSlot.HAND,
            action,
            null,
            null,
            null,
            false,
            ray,
            8.0D
        );
        return new PlayerInputContext<>(
            player.getUniqueId(), 1L, InputFamily.RIGHT_CLICK, InputSource.SYNTHETIC, snapshot
        );
    }
}
