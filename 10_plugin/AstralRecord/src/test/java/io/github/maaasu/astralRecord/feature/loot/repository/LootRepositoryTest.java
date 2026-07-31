package io.github.maaasu.astralRecord.feature.loot.repository;

import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LootRepositoryTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-リポジトリ.md
     * 章・見出し: # 06_3-リポジトリ > ## 2. JSON パース > ### ルートプールオブジェクトパース（内部補助）
     * 検証契約: pick未指定時はcontents件数、空contents時は0を使う。
     */
    @Test
    void missingPickDefaultsToContentCountIncludingEmptyPool() throws Exception {
        LootRepository repository = new LootRepository();
        Method parsePool = LootRepository.class.getDeclaredMethod("parsePool", String.class);
        parsePool.setAccessible(true);

        LootPoolModel populated = (LootPoolModel) parsePool.invoke(repository, """
            {"id":"populated","pick":null,"contents":[
              {"itemId":"item:first","rate":100,"amount":"1"},
              {"itemId":"item:second","rate":100,"amount":"1"}
            ]}
            """);
        LootPoolModel empty = (LootPoolModel) parsePool.invoke(
            repository,
            "{\"id\":\"empty\",\"contents\":[]}"
        );

        assertEquals(2, populated.getMinPick());
        assertEquals(2, populated.getMaxPick());
        assertEquals(0, empty.getMinPick());
        assertEquals(0, empty.getMaxPick());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-リポジトリ.md
     * 章・見出し: # 06_3-リポジトリ > ## 2. JSON パース > ### ルートテーブルオブジェクトパース（内部補助）
     * 検証契約: 降順rangeを昇順へ正規化しrolls未指定を1〜1にする。
     */
    @Test
    void descendingRangesAreNormalizedAndMissingRollsDefaultToOne() throws Exception {
        LootRepository repository = new LootRepository();
        Method parsePool = LootRepository.class.getDeclaredMethod("parsePool", String.class);
        Method parseTable = LootRepository.class.getDeclaredMethod("parseTable", String.class);
        parsePool.setAccessible(true);
        parseTable.setAccessible(true);

        LootPoolModel pool = (LootPoolModel) parsePool.invoke(repository, """
            {"id":"range_pool","pick":"3~1","contents":[
              {"itemId":"item:first","rate":100},
              {"itemId":"item:second","rate":100},
              {"itemId":"item:third","rate":100}
            ]}
            """);
        Object descendingTable = parseTable.invoke(
            repository,
            "{\"id\":\"descending\",\"rolls\":\"2~0\",\"pools\":[]}"
        );
        Object defaultTable = parseTable.invoke(
            repository,
            "{\"id\":\"default\",\"pools\":[]}"
        );

        assertEquals(1, pool.getMinPick());
        assertEquals(3, pool.getMaxPick());
        assertEquals(0, readIntProperty(descendingTable, "getMinRolls"));
        assertEquals(2, readIntProperty(descendingTable, "getMaxRolls"));
        assertEquals(1, readIntProperty(defaultTable, "getMinRolls"));
        assertEquals(1, readIntProperty(defaultTable, "getMaxRolls"));
    }

    private int readIntProperty(Object target, String getterName) throws Exception {
        Method getter = target.getClass().getDeclaredMethod(getterName);
        getter.setAccessible(true);
        return (int) getter.invoke(target);
    }
}
