package io.github.maaasu.astralRecord.feature.pet.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetBagAccessTest {
    private final UUID account = UUID.randomUUID();
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 装備と施設
     * 検証契約: 施設共通の素材集計はGAME BAGに限定し、ホットバー・倉庫・管理者プロファイルの在庫を使用しない。
     */
    @Test void excludesOtherInventoriesAndProfilesFromFacilityPayment() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        add(state, InventoryType.BAG, "GAME", true, false, 3);
        add(state, InventoryType.HOTBAR, "GAME", true, false, 10);
        add(state, InventoryType.STORAGE, "GAME", true, false, 20);
        add(state, InventoryType.BAG, "ADMIN", true, false, 30);
        assertEquals(3, PetBagAccess.materialAmount(PetBagAccess.entries(state), "material"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 装備と施設
     * 検証契約: 施設共通の支払いに個体entryと削除済みentryを混入せず、同一素材の通常entryだけを合算する。
     */
    @Test void excludesInstancesAndDeletedEntriesFromMaterialTotals() {
        UUID bag = UUID.randomUUID();
        var entries = List.of(entry(bag, "material", 2, null, null, false),
            entry(bag, "MATERIAL", 4, null, null, false),
            entry(bag, "material", 8, "PET_EGG", UUID.randomUUID(), false),
            entry(bag, "material", 16, "PET", null, false),
            entry(bag, "material", 32, null, UUID.randomUUID(), false),
            entry(bag, "material", 64, null, null, true),
            entry(bag, "another", 128, null, null, false));
        assertEquals(6, PetBagAccess.materialAmount(entries, "material"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 装備と施設
     * 検証契約: 無効化・削除・未読込のGAME BAGを施設操作対象にしない。
     */
    @Test void excludesUnavailableBags() {
        PlayerInventoryState disabled = new PlayerInventoryState(account);
        add(disabled, InventoryType.BAG, "GAME", false, false, 10);
        PlayerInventoryState deleted = new PlayerInventoryState(account);
        add(deleted, InventoryType.BAG, "GAME", true, true, 10);
        assertTrue(PetBagAccess.entries(disabled).isEmpty());
        assertTrue(PetBagAccess.entries(deleted).isEmpty());
        assertTrue(PetBagAccess.entries(null).isEmpty());
    }

    private void add(PlayerInventoryState state, InventoryType type, String profile, boolean enabled, boolean deleted, long amount) {
        UUID id = UUID.randomUUID();
        state.putInventory(new InventoryModel(id, account, type, profile, 100, enabled, null, TIME, TIME, account, account, deleted));
        state.replaceEntries(id, List.of(entry(id, "material", amount, null, null, false)));
    }
    private InventoryEntryModel entry(UUID bag, String item, long amount, String type, UUID instance, boolean deleted) {
        return new InventoryEntryModel(UUID.randomUUID(), bag, 1, "material", item, type, instance, amount, null, TIME, TIME, account, account, deleted);
    }
}
