package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobRepositoryDefenseEligibilityTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-リポジトリ.md
     * 章・見出し: # 12_3-リポジトリ > ## 3. template parse（内部）
     * 検証契約: 防衛戦参加フラグがレベル別テンプレートへ解決されても維持される。
     */
    @Test
    void enabledEnemyKeepsEligibilityAcrossLevelResolution() {
        MobTemplate template = parse("ENEMY", "true", ",\"levels\":[{\"level\":2},{\"level\":5}]");

        assertTrue(template.defenseEligible());
        assertTrue(template.resolveLevel(5).defenseEligible());
        assertTrue(template.withLevelProfiles(template.levelProfiles()).defenseEligible());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-リポジトリ.md
     * 章・見出し: # 12_3-リポジトリ > ## 3. template parse（内部）
     * 検証契約: 欠落・型不一致・敵以外の防衛戦参加フラグは候補にならない。
     */
    @Test
    void absentWrongTypeAndOtherCategoriesAreIneligible() {
        assertFalse(parse("ENEMY", null, "").defenseEligible());
        assertFalse(parse("ENEMY", "\"true\"", "").defenseEligible());
        assertFalse(parse("BOSS", "true", "").defenseEligible());
        assertFalse(parse("NPC", "true", "").defenseEligible());
    }

    private MobTemplate parse(String category, String eligible, String extra) {
        String flag = eligible == null ? "" : ",\"defenseEligible\":" + eligible;
        String json = "{\"id\":\"defense_test\",\"category\":\"" + category
                + "\",\"entityType\":\"ZOMBIE\",\"name\":\"Test\",\"level\":2"
                + flag + extra + "}";
        return new MobRepository().parseTemplate(JsonParser.parseString(json).getAsJsonObject());
    }
}
