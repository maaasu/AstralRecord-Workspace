package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemWeaponAttackEventHandlerTest extends MockBukkitTestBase {

    @AfterEach
    void clearCache() {
        AstPlayerCache.clear();
    }

    @Test
    void leftClickOnNpcSuppressesWeaponAttack() {
        ItemWeaponAttackService attackService = mock(ItemWeaponAttackService.class);
        SkillActionRingService actionRingService = mock(SkillActionRingService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        MobService mobService = mock(MobService.class);
        ItemWeaponAttackEventHandler handler = new ItemWeaponAttackEventHandler(
                attackService,
                actionRingService,
                skillTreeService,
                mobService
        );
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = mockAstPlayer(player);
        AstPlayerCache.put(astPlayer);

        when(skillTreeService.isSkillTreeEditing(player)).thenReturn(false);
        when(actionRingService.isAttackSuppressed(player)).thenReturn(false);
        when(actionRingService.isOpen(player)).thenReturn(false);
        when(mobService.findTargetedNpc(
                player,
                MobService.NPC_INTERACTION_DISTANCE,
                MobService.NPC_INTERACTION_RAY_SIZE
        )).thenReturn(mock(MobInstance.class));

        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.LEFT_CLICK_AIR,
                null,
                null,
                null,
                EquipmentSlot.HAND
        );

        handler.onPlayerInteract(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        assertEquals(Event.Result.DENY, event.useItemInHand());
        verify(attackService, never()).handleLeftClick(astPlayer, player.getEyeLocation());
        verify(attackService, never()).handleRightClick(astPlayer, player.getEyeLocation());
    }

    @Test
    void rightClickOnNpcSuppressesWeaponAttack() {
        ItemWeaponAttackService attackService = mock(ItemWeaponAttackService.class);
        SkillActionRingService actionRingService = mock(SkillActionRingService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        MobService mobService = mock(MobService.class);
        ItemWeaponAttackEventHandler handler = new ItemWeaponAttackEventHandler(
                attackService,
                actionRingService,
                skillTreeService,
                mobService
        );
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = mockAstPlayer(player);
        AstPlayerCache.put(astPlayer);

        when(skillTreeService.isSkillTreeEditing(player)).thenReturn(false);
        when(actionRingService.isAttackSuppressed(player)).thenReturn(false);
        when(actionRingService.isOpen(player)).thenReturn(false);
        when(mobService.findTargetedNpc(
                player,
                MobService.NPC_INTERACTION_DISTANCE,
                MobService.NPC_INTERACTION_RAY_SIZE
        )).thenReturn(mock(MobInstance.class));

        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_AIR,
                null,
                null,
                null,
                EquipmentSlot.HAND
        );

        handler.onPlayerInteract(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        assertEquals(Event.Result.DENY, event.useItemInHand());
        verify(attackService, never()).handleLeftClick(astPlayer, player.getEyeLocation());
        verify(attackService, never()).handleRightClick(astPlayer, player.getEyeLocation());
    }

    private AstPlayer mockAstPlayer(PlayerMock player) {
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = new AccountModel(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test-account",
                0,
                true,
                AccountMode.PLAYER,
                "{}",
                LocalDateTime.now(),
                LocalDateTime.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                1,
                0L
        );
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getAccount()).thenReturn(account);
        return astPlayer;
    }
}
