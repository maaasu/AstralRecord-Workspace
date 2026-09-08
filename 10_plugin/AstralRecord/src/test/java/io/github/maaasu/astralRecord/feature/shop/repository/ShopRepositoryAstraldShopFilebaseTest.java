package io.github.maaasu.astralRecord.feature.shop.repository;

import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopRepositoryAstraldShopFilebaseTest {

    @TempDir
    Path tempDirectory;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_0-概要.md
     * 章・見出し: # 20_0-概要 > ## 現在の契約
     * 検証契約: アストラルドshopのfixtureから、ストレージ拡張トークンとマーケット拡張トークンdeltaの配置・価格・必要素材を正しく読み込む。
     */
    @Test
    void loadsAstraldShopExpansionEntriesFromFixture() throws IOException {
        Path directory = tempDirectory.resolve("45.features.shop");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("v1.astrald_shop.yml"), """
                schemaVersion: 1
                id: astrald_shop
                name: "アストラルドショップ"
                mode: SHOP
                access: NPC_ONLY
                items:
                  - id: 99a00009
                    itemId:
                      ref: item:99a00009
                    category: currency
                    amount: 1
                    page: 1
                    slot: 0
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:99a00008
                        category: currency
                        amount: 100
                  - id: 99a00010
                    itemId:
                      ref: item:99a00010
                    category: currency
                    amount: 1
                    page: 1
                    slot: 1
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:99a00008
                        category: currency
                        amount: 300
                  - id: 99a00011
                    itemId:
                      ref: item:99a00011
                    category: currency
                    amount: 1
                    page: 1
                    slot: 7
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:99a00008
                        category: currency
                        amount: 50
                  - id: 99a00012
                    itemId:
                      ref: item:99a00012
                    category: currency
                    amount: 1
                    page: 1
                    slot: 8
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:99a00008
                        category: currency
                        amount: 100
                  - id: 99a00013
                    itemId:
                      ref: item:99a00013
                    category: currency
                    amount: 1
                    page: 1
                    slot: 9
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:99a00008
                        category: currency
                        amount: 150
                  - id: 99a00014
                    itemId:
                      ref: item:99a00014
                    category: currency
                    amount: 1
                    page: 1
                    slot: 10
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:99a00008
                        category: currency
                        amount: 200
                """);

        List<ShopDefinition> shops = FileDatabaseManager.getInstance().withReloadSnapshot(
                new FileDatabaseManager.ReloadSnapshot(tempDirectory.toFile()),
                () -> new ShopRepository().loadSnapshot()
        );

        ShopDefinition shop = shops.stream()
                .filter(definition -> definition.id().equals("astrald_shop"))
                .findFirst()
                .orElseThrow();
        assertEquals(new ShopCostItem("99a00008", "currency", 100), entry(shop, "99a00009").requiredItems().getFirst());
        assertEquals(0, entry(shop, "99a00009").slot());
        assertEquals(1, entry(shop, "99a00010").slot());
        assertEquals(7, entry(shop, "99a00011").slot());
        assertEquals(8, entry(shop, "99a00012").slot());
        assertEquals(9, entry(shop, "99a00013").slot());
        assertEquals(10, entry(shop, "99a00014").slot());
        assertEquals(List.of(new ShopCostItem("99a00008", "currency", 200)),
                entry(shop, "99a00014").requiredItems());
    }

    private ShopEntry entry(ShopDefinition shop, String id) {
        return shop.entries().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

}
