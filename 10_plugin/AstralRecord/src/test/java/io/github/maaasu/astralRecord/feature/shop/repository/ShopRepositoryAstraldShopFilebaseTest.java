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
                  - id: storage_expansion_token
                    itemId:
                      ref: item:storage_expansion_token
                    category: currency
                    amount: 1
                    page: 1
                    slot: 0
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:astrald
                        category: currency
                        amount: 100
                  - id: storage_cloud_access_token
                    itemId:
                      ref: item:storage_cloud_access_token
                    category: currency
                    amount: 1
                    page: 1
                    slot: 1
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:astrald
                        category: currency
                        amount: 300
                  - id: market_expansion_token_alpha
                    itemId:
                      ref: item:market_expansion_token_alpha
                    category: currency
                    amount: 1
                    page: 1
                    slot: 7
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:astrald
                        category: currency
                        amount: 50
                  - id: market_expansion_token_beta
                    itemId:
                      ref: item:market_expansion_token_beta
                    category: currency
                    amount: 1
                    page: 1
                    slot: 8
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:astrald
                        category: currency
                        amount: 100
                  - id: market_expansion_token_gamma
                    itemId:
                      ref: item:market_expansion_token_gamma
                    category: currency
                    amount: 1
                    page: 1
                    slot: 9
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:astrald
                        category: currency
                        amount: 150
                  - id: market_expansion_token_delta
                    itemId:
                      ref: item:market_expansion_token_delta
                    category: currency
                    amount: 1
                    page: 1
                    slot: 10
                    priceGold: 0
                    requiredItems:
                      - itemId:
                          ref: item:astrald
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
        assertEquals(new ShopCostItem("astrald", "currency", 100), entry(shop, "storage_expansion_token").requiredItems().getFirst());
        assertEquals(0, entry(shop, "storage_expansion_token").slot());
        assertEquals(1, entry(shop, "storage_cloud_access_token").slot());
        assertEquals(7, entry(shop, "market_expansion_token_alpha").slot());
        assertEquals(8, entry(shop, "market_expansion_token_beta").slot());
        assertEquals(9, entry(shop, "market_expansion_token_gamma").slot());
        assertEquals(10, entry(shop, "market_expansion_token_delta").slot());
        assertEquals(List.of(new ShopCostItem("astrald", "currency", 200)),
                entry(shop, "market_expansion_token_delta").requiredItems());
    }

    private ShopEntry entry(ShopDefinition shop, String id) {
        return shop.entries().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

}
