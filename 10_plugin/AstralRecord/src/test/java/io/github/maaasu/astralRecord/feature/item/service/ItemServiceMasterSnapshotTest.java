package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrb;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEnchantOperation;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbRankMode;
import io.github.maaasu.astralRecord.feature.item.model.ItemSummary;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemServiceMasterSnapshotTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 1. アイテムマスタロード > ### アイテムマスタスナップショット置換
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 3. 所有インスタンス > ### 共通エンチャントマスタ取得
     * 検証契約: itemと共通enchant masterはimmutableな単一snapshotとして同時公開し、cache-only参照はrepository追加通信を行わず新世代のペアだけを返す。
     */
    @Test
    void itemAndEnchantMasterPublishAsOneImmutableCacheOnlySnapshot() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        ItemModel previousItem = DesignTestFixtures.item("previous_item", ItemCategory.MATERIAL, 64);
        EnchantMaster previousMaster = new EnchantMaster(1, "previous_enchant", List.of());
        ItemModel currentItem = DesignTestFixtures.item("current_item", ItemCategory.MATERIAL, 64);
        EnchantMaster currentMaster = new EnchantMaster(1, "current_enchant", List.of());
        service.replaceMasterDataSnapshot(new ItemService.MasterDataSnapshot(
            Map.of(previousItem.getId(), previousItem),
            Map.of(previousMaster.getId(), previousMaster)
        ));

        ItemService.MasterDataSnapshot current = new ItemService.MasterDataSnapshot(
            Map.of(currentItem.getId(), currentItem),
            Map.of(currentMaster.getId(), currentMaster)
        );
        service.replaceMasterDataSnapshot(current);

        assertNull(service.findLoadedById(previousItem.getId()));
        assertNull(service.findEnchantMasterById(previousMaster.getId()));
        assertSame(currentItem, service.findLoadedById(currentItem.getId()));
        assertSame(currentMaster, service.findEnchantMasterById(currentMaster.getId()));
        assertThrows(UnsupportedOperationException.class,
            () -> current.items().put("forbidden", currentItem));
        assertThrows(UnsupportedOperationException.class,
            () -> current.enchantMasters().put("forbidden", currentMaster));
        verify(repository, never()).findEnchantMasterById(currentMaster.getId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 1. アイテムマスタロード > ### アイテムマスタスナップショット構築
     * 検証契約: reload中のitem detail取得が失敗した場合は準備snapshotを公開せず、旧item・enchant master snapshotを一緒に維持する。
     */
    @Test
    void itemDetailFailureLeavesPreviouslyPublishedSnapshotUntouched() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = serviceWithPreviousSnapshot(repository);
        when(repository.findAll()).thenReturn(List.of(
            new ItemSummary("broken_item", ItemCategory.MATERIAL.getApiValue())
        ));
        when(repository.findById("broken_item", ItemCategory.MATERIAL.getApiValue()))
            .thenThrow(new IllegalStateException("detail unavailable"));

        assertThrows(IllegalStateException.class, service::loadMasterDataSnapshot);

        assertPreviousSnapshot(service, repository);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 1. アイテムマスタロード > ### アイテムマスタスナップショット構築
     * 検証契約: reload中の共通enchant master取得が失敗した場合もitemだけを先行公開せず、旧世代のitem・enchant masterペアを維持する。
     */
    @Test
    void enchantMasterFailureLeavesPreviouslyPublishedSnapshotUntouched() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = serviceWithPreviousSnapshot(repository);
        ItemModel enchantOrb = enchantOrb("reload_orb", "missing_enchant");
        when(repository.findAll()).thenReturn(List.of(
            new ItemSummary(enchantOrb.getId(), ItemCategory.ORB.getApiValue())
        ));
        when(repository.findById(enchantOrb.getId(), ItemCategory.ORB.getApiValue()))
            .thenReturn(enchantOrb);
        when(repository.findEnchantMasterById("missing_enchant"))
            .thenThrow(new IllegalStateException("enchant unavailable"));

        assertThrows(IllegalStateException.class, service::loadMasterDataSnapshot);

        assertPreviousSnapshot(service, repository);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 1. アイテムマスタロード > ### 個別アイテムロード
     * 検証契約: ENCHANTオーブの単品ロードは参照masterを先に解決し、itemと同じsnapshotで公開する。
     */
    @Test
    void loadItemPublishesReferencedEnchantMasterAtomically() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = serviceWithPreviousSnapshot(repository);
        ItemModel orb = enchantOrb("dynamic_orb", "dynamic_enchant");
        EnchantMaster master = new EnchantMaster(1, "dynamic_enchant", List.of());
        when(repository.findById(orb.getId(), ItemCategory.ORB.getApiValue())).thenReturn(orb);
        when(repository.findEnchantMasterById(master.getId())).thenReturn(master);

        assertSame(orb, service.loadItem(orb.getId(), ItemCategory.ORB.getApiValue()));

        assertSame(orb, service.findLoadedById(orb.getId()));
        assertSame(master, service.findEnchantMasterById(master.getId()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 1. アイテムマスタロード > ### 個別アイテムロード
     * 検証契約: 単品ロードの参照master取得が失敗した場合はitemだけを公開せず、旧snapshotを維持する。
     */
    @Test
    void loadItemDependencyFailureKeepsPreviousSnapshot() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = serviceWithPreviousSnapshot(repository);
        ItemModel orb = enchantOrb("broken_dynamic_orb", "missing_dynamic_enchant");
        when(repository.findById(orb.getId(), ItemCategory.ORB.getApiValue())).thenReturn(orb);
        when(repository.findEnchantMasterById("missing_dynamic_enchant"))
            .thenThrow(new IllegalStateException("enchant unavailable"));

        assertThrows(IllegalStateException.class,
            () -> service.loadItem(orb.getId(), ItemCategory.ORB.getApiValue()));

        assertNull(service.findLoadedById(orb.getId()));
        assertPreviousSnapshot(service, repository);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 1. アイテムマスタロード > ### カテゴリ単位ロード
     * 検証契約: カテゴリ再ロードは同じIDの共通enchant masterも新世代へ更新する。
     */
    @Test
    void loadAllByCategoryRefreshesChangedEnchantMaster() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = serviceWithPreviousSnapshot(repository);
        ItemModel orb = enchantOrb("category_orb", "shared_enchant");
        EnchantMaster oldMaster = new EnchantMaster(1, "shared_enchant", List.of());
        EnchantMaster newMaster = new EnchantMaster(2, "shared_enchant", List.of());
        service.replaceMasterDataSnapshot(new ItemService.MasterDataSnapshot(
            Map.of("previous_item",
                DesignTestFixtures.item("previous_item", ItemCategory.MATERIAL, 64)),
            Map.of(oldMaster.getId(), oldMaster)
        ));
        when(repository.findAllByCategory(ItemCategory.ORB.getApiValue())).thenReturn(List.of(orb));
        when(repository.findEnchantMasterById(newMaster.getId())).thenReturn(newMaster);

        assertEquals(1, service.loadAllByCategory(ItemCategory.ORB.getApiValue()));

        assertSame(orb, service.findLoadedById(orb.getId()));
        assertSame(newMaster, service.findEnchantMasterById(newMaster.getId()));
    }

    private ItemService serviceWithPreviousSnapshot(ItemRepository repository) {
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        service.replaceMasterDataSnapshot(new ItemService.MasterDataSnapshot(
            Map.of("previous_item",
                DesignTestFixtures.item("previous_item", ItemCategory.MATERIAL, 64)),
            Map.of("previous_enchant",
                new EnchantMaster(1, "previous_enchant", List.of()))
        ));
        return service;
    }

    private void assertPreviousSnapshot(ItemService service, ItemRepository repository) {
        assertEquals("previous_item", service.findLoadedById("previous_item").getId());
        assertEquals("previous_enchant", service.findEnchantMasterById("previous_enchant").getId());
        verify(repository, never()).findEnchantMasterById("previous_enchant");
    }

    private ItemModel enchantOrb(String itemId, String enchantMasterId) {
        ItemOrbEffect effect = new ItemOrbEffect(
            ItemOrbEffectType.ENCHANT,
            List.of(),
            null,
            ItemOrbRankMode.EXACT,
            null,
            false,
            enchantMasterId,
            ItemOrbEnchantOperation.FILL_ONE_EMPTY
        );
        return new ItemModel(
            1,
            itemId,
            ItemCategory.ORB.getApiValue(),
            itemId,
            "AMETHYST_SHARD",
            "common",
            64,
            0,
            null,
            null,
            List.of(),
            false,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            new ItemOrb(effect)
        );
    }
}
