package io.github.maaasu.astralRecord.feature.pet.model;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetGrowthCalculatorTest {
    private static final PetMaster MASTER = new PetMaster(JsonParser.parseString("""
        {"rules":{"maxLevel":100,"sizeGrowth":{"initialScale":0.5,"matureScale":1.0,"matureLevel":20}},
         "experience":{"base":500,"quadratic":120,"tierInterval":10,"tierBonus":850,
           "wave":[0,90,35,140,60,185,95,230],"milestoneInterval":5,
           "milestoneBase":600,"milestoneLinear":80}}
        """).getAsJsonObject());

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 成長と遺伝
     * 検証契約: APIの整数tier加算・wave・節目を含む必要EXPとレベル内残量を一致させる。
     */
    @Test void matchesApiExperienceCurveAndCapsDisplayAtMaxLevel() {
        assertEquals(620, PetGrowthCalculator.requiredExperience(MASTER, 1));
        assertEquals(14840, PetGrowthCalculator.requiredExperience(MASTER, 10));
        assertEquals(52540, PetGrowthCalculator.requiredExperience(MASTER, 20));
        var progress = PetGrowthCalculator.progress(MASTER, 10, 7420);
        assertEquals(7420, progress.currentExperience());
        assertEquals(14840, progress.requiredExperience());
        assertEquals(.5, progress.ratio(), 1e-12);
        var max = PetGrowthCalculator.progress(MASTER, 100, 12345);
        assertTrue(max.maxLevel());
        assertEquals(0, max.currentExperience());
        assertEquals(0, max.requiredExperience());
        assertEquals(1, max.ratio());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 成長と遺伝
     * 検証契約: 基礎サイズの個体差を保持し、Lv1は半分、Lv20で成体になり以後増えない。
     */
    @Test void sizeGrowthPreservesIndividualDifferenceAndStopsAtMatureLevel() {
        assertEquals(.4, PetGrowthCalculator.effectiveSize(MASTER, .8, 1), 1e-12);
        assertEquals(.6, PetGrowthCalculator.effectiveSize(MASTER, 1.2, 1), 1e-12);
        assertEquals(.8 * (.5 + .5 * 9 / 19), PetGrowthCalculator.effectiveSize(MASTER, .8, 10), 1e-12);
        assertEquals(.8, PetGrowthCalculator.effectiveSize(MASTER, .8, 20), 1e-12);
        assertEquals(.8, PetGrowthCalculator.effectiveSize(MASTER, .8, 100), 1e-12);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 成長と遺伝
     * 検証契約: 活動種別の独自係数と対象レベルだけでEXPを計算し、レベル下限と整数化を守る。
     */
    @Test void activityExperienceUsesIndependentCoefficientsAndTargetLevel() {
        PetMaster master = new PetMaster(JsonParser.parseString("""
            {"experience":{"combatGain":{"base":7.5,"perLevel":2.5},
              "gatheringGain":{"base":3,"perLevel":4}}}
            """).getAsJsonObject());
        assertEquals(10, PetGrowthCalculator.combatExperience(master, 1));
        assertEquals(32, PetGrowthCalculator.combatExperience(master, 10));
        assertEquals(10, PetGrowthCalculator.combatExperience(master, 0));
        assertEquals(7, PetGrowthCalculator.gatheringExperience(master, 1));
        assertEquals(43, PetGrowthCalculator.gatheringExperience(master, 10));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 成長と遺伝
     * 検証契約: 基礎能力上昇でEXP倍率が単調減少し、潜在・育成済み能力には依存しない。
     */
    @Test void growthRateFallsWithBaseStatsAndIgnoresCurrentStatsAndPotential() {
        JsonObject definitions = new JsonObject();
        JsonObject stats = new JsonObject();
        for (String id : java.util.List.of("VITALITY", "POWER", "DEFENSE", "EVASION", "SUPPORT")) {
            definitions.add(id, JsonParser.parseString("{\"tiers\":[{\"min\":10,\"max\":30},{\"min\":31,\"max\":110}]}"));
            stats.add(id, JsonParser.parseString("{\"baseValue\":10,\"currentValue\":999,\"potential\":true}"));
        }
        JsonObject data = JsonParser.parseString("{\"experience\":{\"growthRateMin\":0.2},\"species\":[{\"id\":\"test\"}]}").getAsJsonObject();
        data.getAsJsonArray("species").get(0).getAsJsonObject().add("stats", definitions);
        PetMaster master = new PetMaster(data);
        JsonObject details = new JsonObject();
        details.add("stats", stats);
        assertEquals(1, PetGrowthCalculator.growthRate(master, "test", details), 1e-12);
        stats.getAsJsonObject("POWER").addProperty("baseValue", 60);
        assertEquals(.92, PetGrowthCalculator.growthRate(master, "test", details), 1e-12);
        for (var row : stats.entrySet()) row.getValue().getAsJsonObject().addProperty("baseValue", 110);
        assertEquals(.2, PetGrowthCalculator.growthRate(master, "test", details), 1e-12);
        for (var row : stats.entrySet()) {
            row.getValue().getAsJsonObject().addProperty("currentValue", 1);
            row.getValue().getAsJsonObject().addProperty("potential", false);
        }
        assertEquals(.2, PetGrowthCalculator.growthRate(master, "test", details), 1e-12);
    }
}
