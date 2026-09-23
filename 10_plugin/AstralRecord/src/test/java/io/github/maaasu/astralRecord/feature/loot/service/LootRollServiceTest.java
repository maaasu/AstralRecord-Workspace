package io.github.maaasu.astralRecord.feature.loot.service;

import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootRollResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootRollServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-サービス.md
     * 章・見出し: # 06_3-サービス > ## 2. LootRollService > ### ルートテーブル抽選
     * 検証契約: 0〜100へclampした独立確率判定でmissを許し100%を必ず採用する。
     */
    @Test
    void rollAllowsMissesAndAlwaysAcceptsHundredPercent() {
        LootModel loot = fixedLoot(
            3,
            List.of(
                new LootContent("zero", 1, 1, 0.0D),
                new LootContent("miss", 1, 1, 50.0D),
                new LootContent("always", 1, 1, 100.0D)
            )
        );

        List<LootRollResult> results = new LootRollService().roll(
            loot,
            new ScriptedRandom(List.of(), List.of(75.0D))
        );

        assertEquals(1, results.size());
        assertEquals("always", results.getFirst().getItemId());
        assertEquals(100.0D, results.getFirst().getConfiguredRate());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-サービス.md
     * 章・見出し: # 06_3-サービス > ## 2. LootRollService > ### ルートテーブル抽選
     * 検証契約: 独立成功候補をpick上限まで無作為に絞る。
     */
    @Test
    void rollUsesPickAsMaximumAfterIndependentSuccesses() {
        LootModel loot = fixedLoot(
            2,
            List.of(
                new LootContent("first", 1, 1, 100.0D),
                new LootContent("second", 1, 1, 100.0D),
                new LootContent("third", 1, 1, 100.0D)
            )
        );

        List<LootRollResult> results = new LootRollService().roll(
            loot,
            new ScriptedRandom(List.of(2, 1), List.of())
        );

        assertEquals(2, results.size());
        assertEquals(2, new HashSet<>(results.stream().map(LootRollResult::getItemId).toList()).size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-サービス.md
     * 章・見出し: # 06_3-サービス > ## 2. LootRollService > ### ルートテーブル抽選
     * 検証契約: 当選結果へclamp済み設定rateを保持する。
     */
    @Test
    void rollPreservesConfiguredDropRateForSuccessfulResult() {
        LootModel loot = fixedLoot(
            1,
            List.of(new LootContent("rare", 1, 1, 1.5D))
        );

        List<LootRollResult> results = new LootRollService().roll(
            loot,
            new ScriptedRandom(List.of(), List.of(0.0D))
        );

        assertEquals(1, results.size());
        assertEquals(1.5D, results.getFirst().getConfiguredRate());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-サービス.md
     * 章・見出し: # 06_3-サービス > ## 2. LootRollService > ### ルートテーブル抽選
     * 検証契約: 設定率20%はDROP_RATE_INCREASE=200%により40%となり、40未満の乱数で当選する。
     */
    @Test
    void rollAppliesPlayerDropRateIncreaseToConfiguredRate() {
        LootModel loot = fixedLoot(
            1,
            List.of(new LootContent("doubled_rate", 1, 1, 20.0D))
        );

        List<LootRollResult> results = new LootRollService().roll(
            loot,
            200.0D,
            new ScriptedRandom(List.of(), List.of(39.9D))
        );

        assertEquals(1, results.size());
        assertEquals("doubled_rate", results.getFirst().getItemId());
        assertEquals(20.0D, results.getFirst().getConfiguredRate());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-サービス.md
     * 章・見出し: # 06_3-サービス > ## 2. LootRollService > ### ルートテーブル抽選
     * 検証契約: 範囲のDROP_RATE_INCREASEを各contentの独立確率判定ごとに再ロールする。
     */
    @Test
    void rollResolvesDropRateSeparatelyForEachContentChance() {
        LootModel loot = fixedLoot(
            2,
            List.of(
                new LootContent("boosted", 1, 1, 20.0D),
                new LootContent("base_rate", 1, 1, 20.0D)
            )
        );
        Deque<Double> dropRateSamples = new ArrayDeque<>(List.of(200.0D, 100.0D));

        List<LootRollResult> results = new LootRollService().roll(
            loot,
            dropRateSamples::removeFirst,
            new ScriptedRandom(List.of(), List.of(39.9D, 39.9D))
        );

        assertEquals(1, results.size());
        assertEquals("boosted", results.getFirst().getItemId());
        assertTrue(dropRateSamples.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-サービス.md
     * 章・見出し: # 06_3-サービス > ## 2. LootRollService > ### ルートテーブル抽選
     * 検証契約: roll/pick回数を閉区間から抽選し常に上限値を使わない。
     */
    @Test
    void rollSamplesRollAndPickRangesInsteadOfUsingTheirUpperBounds() {
        LootModel loot = new LootModel(
            1,
            "range_table",
            "range_table",
            1,
            2,
            List.of(new LootPoolModel(
                "range_pool",
                1,
                2,
                List.of(
                    new LootContent("first", 1, 1, 100.0D),
                    new LootContent("second", 1, 1, 100.0D)
                )
            ))
        );

        List<LootRollResult> results = new LootRollService().roll(
            loot,
            new ScriptedRandom(List.of(1, 1, 0), List.of())
        );

        assertEquals(1, results.size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-サービス.md
     * 章・見出し: # 06_3-サービス > ## 2. LootRollService > ### ルートテーブル抽選
     * 検証契約: pickが0に解決したpoolから結果を生成しない。
     */
    @Test
    void rollReturnsNoResultsWhenPickRangeResolvesToZero() {
        LootModel loot = new LootModel(
            1,
            "empty_table",
            "empty_table",
            1,
            1,
            List.of(new LootPoolModel(
                "empty_pool",
                0,
                0,
                List.of(new LootContent("always", 1, 1, 100.0D))
            ))
        );

        assertTrue(new LootRollService().roll(loot, new ScriptedRandom(List.of(), List.of())).isEmpty());
    }

    private LootModel fixedLoot(int pick, List<LootContent> contents) {
        return new LootModel(
            1,
            "test_table",
            "test_table",
            1,
            List.of(new LootPoolModel("test_pool", pick, contents))
        );
    }

    @SuppressWarnings("serial")
    private static final class ScriptedRandom extends Random {
        private final Deque<Integer> integers;
        private final Deque<Double> doubles;

        private ScriptedRandom(List<Integer> integers, List<Double> doubles) {
            this.integers = new ArrayDeque<>(integers);
            this.doubles = new ArrayDeque<>(doubles);
        }

        @Override
        public int nextInt(int origin, int bound) {
            int value = integers.removeFirst();
            assertTrue(value >= origin && value < bound, "scripted integer is outside requested range");
            return value;
        }

        @Override
        public double nextDouble(double bound) {
            double value = doubles.removeFirst();
            assertTrue(value >= 0.0D && value < bound, "scripted double is outside requested range");
            return value;
        }
    }
}
