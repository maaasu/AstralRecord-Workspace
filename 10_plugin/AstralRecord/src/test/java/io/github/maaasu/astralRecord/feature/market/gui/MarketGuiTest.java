package io.github.maaasu.astralRecord.feature.market.gui;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.market.model.MarketListing;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## 出品一覧・詳細
     * 検証契約: 一致する装備個体は通常装備 tooltip で表示し、identity 不一致時はマスタ表示へ戻す。
     */
    @Test
    void equipmentListingUsesInstanceTooltipAndFallsBackOnIdentityMismatch() {
        ItemService itemService = mock(ItemService.class);
        ItemModel item = DesignTestFixtures.equipmentItem(
            "market_blade",
            "physical_attack",
            ItemEquipmentStatType.FLAT
        );
        when(itemService.findLoadedById(item.getId())).thenReturn(item);
        MarketGui gui = new MarketGui(
            itemService,
            new ItemStackFactory(mock(LootService.class), itemService)
        );
        UUID accountId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        EquipmentInstance equipment = equipment(instanceId, accountId, item.getId());

        ItemStack instanceDisplay = listingItem(
            gui,
            listing(accountId, instanceId, item.getId(), equipment)
        );
        assertEquals(instanceId.toString(), ItemStackFactory.getEquipmentInstanceId(instanceDisplay));
        String instanceName = PlainTextComponentSerializer.plainText().serialize(
            Objects.requireNonNull(instanceDisplay.getItemMeta().displayName())
        );
        assertTrue(instanceName.contains("+ 8"));
        String instanceLore = Objects.requireNonNull(instanceDisplay.getItemMeta().lore()).stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(instanceLore.contains("18"));
        assertTrue(instanceLore.contains("24"));

        ItemStack fallbackDisplay = listingItem(
            gui,
            listing(accountId, UUID.randomUUID(), item.getId(), equipment)
        );
        assertNull(ItemStackFactory.getEquipmentInstanceId(fallbackDisplay));

        ItemStack itemMismatchDisplay = listingItem(
            gui,
            listing(accountId, instanceId, item.getId(), equipment(instanceId, accountId, "another_item"))
        );
        assertNull(ItemStackFactory.getEquipmentInstanceId(itemMismatchDisplay));
    }

    private static ItemStack listingItem(MarketGui gui, MarketListing listing) {
        try {
            Method method = MarketGui.class.getDeclaredMethod("listingItem", MarketListing.class, boolean.class);
            method.setAccessible(true);
            return (ItemStack) method.invoke(gui, listing, false);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static EquipmentInstance equipment(UUID instanceId, UUID accountId, String itemId) {
        return new EquipmentInstance(
            instanceId.toString(),
            accountId.toString(),
            itemId,
            8,
            3,
            2,
            120,
            91,
            "2026-09-16T00:00:00Z",
            "2026-09-16T00:00:00Z",
            List.of(new EquipmentStatRoll(
                UUID.randomUUID().toString(),
                "physical_attack",
                "18",
                "24",
                0
            )),
            List.of(),
            List.of()
        );
    }

    private static MarketListing listing(
        UUID accountId,
        UUID instanceId,
        String itemId,
        EquipmentInstance equipment
    ) {
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        return new MarketListing(
            UUID.randomUUID(),
            accountId,
            "market-seller",
            0,
            null,
            UUID.randomUUID(),
            "equipment",
            itemId,
            "EQUIPMENT",
            instanceId,
            equipment,
            1,
            1,
            "gold",
            500,
            500,
            100,
            null,
            null,
            "HIGH",
            null,
            null,
            "ACTIVE",
            null,
            now,
            now.plusSeconds(86_400),
            null,
            null,
            1,
            now,
            now,
            0,
            List.of(),
            List.of()
        );
    }
}
