package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
