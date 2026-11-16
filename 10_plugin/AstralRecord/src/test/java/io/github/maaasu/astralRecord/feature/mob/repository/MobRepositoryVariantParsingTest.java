package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MobRepositoryVariantParsingTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-リポジトリ.md
     * 章・見出し: # 12_3-リポジトリ > ## 3. template parse（内部）
     * 検証契約: APIのvariant.scaleをMobVariantConfigへ保持する。
     */
    @Test
    void parsesVariantScaleFromApiPayload() {
        String json = """
            {
              "schemaVersion": 1,
              "id": "midgard_savanna_sunbird",
              "type": "MOB",
              "category": "BOSS",
              "name": "sunbird",
              "level": 10,
              "entityType": "PARROT",
              "variant": { "age": "ADULT", "kind": "RED", "scale": 4.0 },
              "baseStats": []
            }
            """;

        MobTemplate template = new MobRepository().parseTemplate(JsonParser.parseString(json).getAsJsonObject());

        assertNotNull(template);
        assertEquals(4.0D, template.variant().scale());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-リポジトリ.md
     * 章・見出し: # 12_3-リポジトリ > ## 3. template parse（内部）
     * 検証契約: 頭アイコンの役割名はレベル別に継承・上書き・解除される。
     */
    @Test
    void resolvesMobHeadModelFromEachLevelProfile() {
        String json = """
            {
              "id": "midgard_savanna_sunbird",
              "category": "BOSS",
              "name": "sunbird",
              "entityType": "PARROT",
              "iconModel": "mob_head_sunbird",
              "levels": [
                { "level": 11, "iconModel": "mob_head_parrot_gray" },
                { "level": 12, "iconModel": null },
                { "level": 10 }
              ]
            }
            """;

        MobTemplate template = new MobRepository().parseTemplate(JsonParser.parseString(json).getAsJsonObject());

        assertNotNull(template);
        assertEquals(10, template.level());
        assertEquals("mob_head_sunbird", template.iconModel());
        assertEquals("mob_head_parrot_gray", template.resolveLevel(11).iconModel());
        assertNull(template.resolveLevel(12).iconModel());
    }
}
