package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseService;
import io.github.maaasu.astralRecord.feature.item.service.PotionUseService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ItemInteractionBlockEventHandlerTest {

    @Test
    void rightClickVanillaGuardRunsAfterNewActionRing() {
        assertEquals(
            InteractionCandidateOrder.RIGHT_CLICK_ITEM_VANILLA_GUARD,
            resolveVanillaGuard(InputFamily.RIGHT_CLICK).stableOrder()
        );
    }

    @Test
    void leftClickVanillaGuardKeepsCombatBlockingOrder() {
        assertEquals(
            InteractionCandidateOrder.ITEM_VANILLA_GUARD,
            resolveVanillaGuard(InputFamily.LEFT_CLICK).stableOrder()
        );
    }

    private PlayerInputCandidate resolveVanillaGuard(InputFamily family) {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        ItemModel model = mock(ItemModel.class);
        when(model.getId()).thenReturn("test_weapon");
        when(model.getCategory()).thenReturn(ItemCategory.EQUIPMENT.getApiValue());
        InventoryService inventoryService = mock(InventoryService.class);
        when(inventoryService.getItemModelInHand(astPlayer, EquipmentSlot.HAND)).thenReturn(model);
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            player,
            mock(Event.class),
            EquipmentSlot.HAND,
            null,
            null,
            null,
            null,
            false,
            PlayerInteractionRayTrace.create(new Vector(), new Vector(0.0D, 0.0D, 1.0D), 8.0D),
            8.0D
        );
        PlayerInputContext<PlayerInteractionSnapshot> context = new PlayerInputContext<>(
            playerId,
            1L,
            family,
            InputSource.SYNTHETIC,
            snapshot
        );
        ItemInteractionBlockEventHandler handler = new ItemInteractionBlockEventHandler(
            inventoryService,
            mock(BundleUseService.class),
            mock(PotionUseService.class)
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            return handler.resolve(context).stream().findFirst().orElseThrow();
        }
    }
}
