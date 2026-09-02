package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MobRepositoryShieldParsingTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-リポジトリ.md
     * 章・見出し: # 12_3-リポジトリ > ## 3. template parse（内部）
     * 検証契約: rechargeAmountの明示値をPlugin payload parse後も維持する。
     */
    @Test
    void parsesExplicitShieldRechargeAmount() {
        MobTemplate template = parseTemplate("\"rechargeAmount\": 25");

        assertNotNull(template);
        assertEquals(25.0D, template.shield().resolvedRechargeAmount(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-リポジトリ.md
     * 章・見出し: # 12_3-リポジトリ > ## 3. template parse（内部）
     * 検証契約: rechargeAmount省略時はshield.maxを完了量として解決する。
     */
    @Test
    void defaultsMissingShieldRechargeAmountToMax() {
        MobTemplate template = parseTemplate(null);

        assertNotNull(template);
        assertEquals(10.0D, template.shield().resolvedRechargeAmount(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-リポジトリ.md
     * 章・見出し: # 12_3-リポジトリ > ## 3. template parse（内部）
     * 検証契約: rechargeAmountがnullの場合もshield.maxを完了量として解決する。
     */
    @Test
    void defaultsNullShieldRechargeAmountToMax() {
        MobTemplate template = parseTemplate("\"rechargeAmount\": null");

        assertNotNull(template);
        assertEquals(10.0D, template.shield().resolvedRechargeAmount(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 2. Mob テンプレート
     * 検証契約: 同一 Mob マスタ内の levels を読み込み、未指定時は最小レベルを実効値にする。
     */
    @Test
    void resolvesLowestLevelProfileWhenLevelIsNotSpecified() {
        MobTemplate template = parseProfileTemplate();

        assertNotNull(template);
        assertEquals(1, template.level());
        assertEquals(10.0D, template.statValue("MAX_HEALTH", 0.0D), 0.0001D);
        assertEquals(2, template.levelProfiles().size());
        assertEquals(1, template.resolveLevel(99).level());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 2. Mob テンプレート
     * 検証契約: 指定レベルは同一マスタ内のプロファイルへ解決される。
     */
    @Test
    void resolvesExplicitLevelProfile() {
        MobTemplate template = parseProfileTemplate();

        MobTemplate levelTwo = template.resolveLevel(2);

        assertEquals(2, levelTwo.level());
        assertEquals("Level Two", levelTwo.displayName());
        assertEquals(20.0D, levelTwo.statValue("MAX_HEALTH", 0.0D), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 2. Mob テンプレート
     * 検証契約: APIのlevel profileに残るref/random形式のdropsを、指定レベルのitem・loot table・EXP・moneyへ解決する。
     */
    @Test
    void resolvesReferenceFormDropsInExplicitLevelProfile() {
        MobTemplate template = parseProfileDropTemplate();
        MobTemplate levelTwo = template.resolveLevel(2);
        MobDropConfig drops = levelTwo.drops();

        assertNotNull(drops);
        assertEquals(20, drops.exp());
        assertNotNull(drops.money());
        assertEquals(3, drops.money().min());
        assertEquals(4, drops.money().max());
        assertEquals(1, drops.items().size());
        assertEquals("level_item", drops.items().getFirst().itemId());
        assertEquals("2~3", drops.items().getFirst().amount());
        assertEquals("level_table", drops.lootTable());
    }

    private static MobTemplate parseTemplate(String rechargeAmountEntry) {
        String shield = rechargeAmountEntry == null
                ? "\"enabled\": true, \"max\": 10, \"rechargeTimeSeconds\": 15"
                : "\"enabled\": true, \"max\": 10, \"rechargeTimeSeconds\": 15, " + rechargeAmountEntry;
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "shield_test",
                  "category": "ENEMY",
                  "name": "Shield Test",
                  "level": 1,
                  "entityType": "ZOMBIE",
                  "baseStats": [],
                  "shield": { %s }
                }
                """.formatted(shield);
        return new MobRepository().parseTemplate(JsonParser.parseString(json).getAsJsonObject());
    }

    private static MobTemplate parseProfileTemplate() {
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "profile_test",
                  "category": "ENEMY",
                  "name": "Level One",
                  "level": 1,
                  "entityType": "ZOMBIE",
                  "baseStats": [{ "status": "MAX_HEALTH", "value": 10 }],
                  "levels": [
                    {
                      "level": 2,
                      "name": "Level Two",
                      "baseStats": [{ "status": "MAX_HEALTH", "value": 20 }]
                    },
                    {
                      "level": 1
                    }
                  ]
                }
                """;
        return new MobRepository().parseTemplate(JsonParser.parseString(json).getAsJsonObject());
    }

    private static MobTemplate parseProfileDropTemplate() {
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "profile_drop_test",
                  "category": "ENEMY",
                  "name": "Level One",
                  "level": 1,
                  "entityType": "ZOMBIE",
                  "baseStats": [],
                  "drops": {
                    "exp": 10,
                    "money": { "min": 1, "max": 2 },
                    "items": [
                      { "itemId": "item:default_item", "rate": 100.0, "amount": "1" }
                    ],
                    "lootTable": "loot_table:default_table"
                  },
                  "levels": [
                    { "level": 1 },
                    {
                      "level": 2,
                      "drops": {
                        "exp": 20,
                        "money": { "min": 3, "max": 4 },
                        "items": [
                          {
                            "itemId": { "ref": "item:level_item" },
                            "rate": 100.0,
                            "amount": { "random": "2~3" },
                            "luckAffected": false,
                            "hidden": false
                          }
                        ],
                        "lootTable": { "ref": "loot_table:level_table" }
                      }
                    }
                  ]
                }
                """;
        return new MobRepository().parseTemplate(JsonParser.parseString(json).getAsJsonObject());
    }
}
