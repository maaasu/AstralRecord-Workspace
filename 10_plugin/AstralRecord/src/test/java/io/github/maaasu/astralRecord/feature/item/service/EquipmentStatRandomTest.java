package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRandom;
import org.junit.jupiter.api.Test;
import java.util.random.RandomGenerator;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentStatRandomTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment` > #### 生成時一回のステータス乱数
     * 検証契約: 0分岐は指定確率の未満だけで選ばれ、通常分岐は両端を含む離散候補になる。
     */
    @Test
    void zeroBranchAndDiscreteEndpointsAreExact() {
        var definition = new EquipmentStatRandom(1, 10, 0.1, 0.95);
        assertEquals("0", definition.sample(0, draw(0.949, 90)).stripTrailingZeros().toPlainString());
        assertEquals("1", definition.sample(0, draw(0.95, 0)).stripTrailingZeros().toPlainString());
        assertEquals("10", definition.sample(0, draw(0.999, 90)).stripTrailingZeros().toPlainString());
        assertEquals("1.1", definition.sample(0, draw(0.95, 1)).stripTrailingZeros().toPlainString());
        assertEquals("92", new EquipmentStatRandom(-8, 8, 1, 0).sample(100, draw(0, 0)).toPlainString());
        assertEquals("108", new EquipmentStatRandom(-8, 8, 1, 0).sample(100, draw(0, 16)).toPlainString());
        assertEquals("0", new EquipmentStatRandom(1, 1, 1, 1).sample(0, draw(0.999, 0)).toPlainString());
        assertEquals("1000000000000000.125", new EquipmentStatRandom(0, 1, 0.125, 0)
                .sample(1e15, draw(0, 1)).stripTrailingZeros().toPlainString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment` > #### 生成時一回のステータス乱数
     * 検証契約: 非有限・精度超過・逆転・不正な刻み・確率・候補数を生成前に拒否する。
     */
    @Test
    void malformedDefinitionsAreRejected() {
        double[][] invalid = {
            {Double.NaN, 1, 1, 0}, {0, Double.POSITIVE_INFINITY, 1, 0},
            {0, 1, Double.NaN, 0}, {0, 1, 1, Double.NaN},
            {2, 1, 1, 0}, {0, 1, 0, 0}, {0, 1, -1, 0},
            {0, 1, 0.3, 0}, {0, 1, 0.00001, 0}, {0.00001, 1, 1, 0},
            {0, 1, 1, -0.1}, {0, 1, 1, 1.1}, {0, 1_000_000, 1, 0}
        };
        for (double[] values : invalid) {
            assertThrows(IllegalArgumentException.class,
                () -> new EquipmentStatRandom(values[0], values[1], values[2], values[3]));
        }
        assertDoesNotThrow(() -> new EquipmentStatRandom(0, 999_999, 1, 0));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment` > #### 生成時一回のステータス乱数
     * 検証契約: 基準値の有限性・保存精度・加算後の有限性と0分岐の区間独立性を検証する。
     */
    @Test
    void baseValidationIncludesAdditionAndZeroOverlap() {
        var random = new EquipmentStatRandom(-1, 1, 1, 0.5);
        assertThrows(IllegalArgumentException.class, () -> random.validateBase(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> random.validateBase(1.00001));
        assertThrows(IllegalArgumentException.class, () -> random.validateBase(0));
        assertThrows(IllegalArgumentException.class, () -> random.validateBase(1));
        assertDoesNotThrow(() -> random.validateBase(2));
        var overflowing = new EquipmentStatRandom(Double.MAX_VALUE, Double.MAX_VALUE, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> overflowing.validateBase(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
            () -> new EquipmentStatRandom(-1, 1, 1, 0).validateBase(1e16));
        assertThrows(IllegalArgumentException.class,
            () -> new EquipmentStatRandom(0, 2, 1, 0).validateBase(1e16));
        assertDoesNotThrow(() -> new EquipmentStatRandom(0, 2, 2, 0).validateBase(1e16));
    }

    private static RandomGenerator draw(double probability, long index) {
        return new RandomGenerator() {
            public long nextLong() { return index; }
            public long nextLong(long bound) {
                assertTrue(index >= 0 && index < bound);
                return index;
            }
            public double nextDouble() { return probability; }
        };
    }
}
