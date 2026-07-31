package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonParser;
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
}
