package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MobRepositorySkillBindingParsingTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 1. 分離方針
     * 検証契約: 旧Mobマスターの文字列スキルIDを読み込み、object形式へ移行するまで起動を妨げない。
     */
    @Test
    void parsesLegacyStringMobSkillBindings() {
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "legacy_skill_test",
                  "category": "ENEMY",
                  "name": "Legacy Skill Test",
                  "level": 1,
                  "entityType": "ZOMBIE",
                  "baseStats": [],
                  "ai": {
                    "combat": {
                      "style": "RANGED",
                      "skills": ["mob_skeleton_bow_shot"]
                    }
                  }
                }
                """;

        MobTemplate template = new MobRepository().parseTemplate(JsonParser.parseString(json).getAsJsonObject());

        assertNotNull(template.combat());
        assertEquals("mob_skeleton_bow_shot", template.combat().skills().getFirst().id());
        assertEquals(0, template.combat().skills().getFirst().params().size());
    }
}
