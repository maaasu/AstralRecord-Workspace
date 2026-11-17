package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MobRepositoryVariantParsingTest {

    /**
     * APIのvariant.scaleをMobVariantConfigへ保持する。
     */
    @Test
    void parsesVariantScaleFromApiPayload() {
        String json = """
            {
              "schemaVersion": 1,
              "id": "fixture_parrot",
              "type": "MOB",
              "category": "BOSS",
              "name": "Fixture Parrot",
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
     * 頭アイコンの役割名はレベル別に継承・上書き・解除される。
     */
    @Test
    void resolvesMobHeadModelFromEachLevelProfile() {
        String json = """
            {
              "id": "fixture_parrot",
              "category": "BOSS",
              "name": "Fixture Parrot",
              "entityType": "PARROT",
              "iconModel": "fixture_head_default",
              "levels": [
                { "level": 11, "iconModel": "fixture_head_override" },
                { "level": 12, "iconModel": null },
                { "level": 10 }
              ]
            }
            """;

        MobTemplate template = new MobRepository().parseTemplate(JsonParser.parseString(json).getAsJsonObject());

        assertNotNull(template);
        assertEquals(10, template.level());
        assertEquals("fixture_head_default", template.iconModel());
        assertEquals("fixture_head_override", template.resolveLevel(11).iconModel());
        assertNull(template.resolveLevel(12).iconModel());
    }
}
