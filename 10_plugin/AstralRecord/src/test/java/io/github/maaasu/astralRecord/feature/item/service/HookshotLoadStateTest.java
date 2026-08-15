package io.github.maaasu.astralRecord.feature.item.service;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookshotLoadStateTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 装填済み状態は inventory entry metadata の hookshot 名前空間へ保存し、他機能の metadata を保持する。
     */
    @Test
    void storesLoadedStateWithoutDiscardingOtherMetadata() {
        HookshotLoadState.Update update = HookshotLoadState.setLoaded(
            "{\"acquiredAt\":\"2026-08-15T00:00:00\",\"other\":{\"value\":1}}",
            true
        );

        assertTrue(update.accepted());
        assertNotNull(update.metadataJson());
        assertTrue(HookshotLoadState.isLoaded(update.metadataJson()));
        assertTrue(JsonParser.parseString(update.metadataJson()).getAsJsonObject().has("acquiredAt"));
        assertTrue(JsonParser.parseString(update.metadataJson()).getAsJsonObject().has("other"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 発射後に装填状態だけを外し、metadata が空になった場合は null を保存する。
     */
    @Test
    void clearsOnlyLoadedStateAfterFire() {
        HookshotLoadState.Update loaded = HookshotLoadState.setLoaded(null, true);
        HookshotLoadState.Update unloaded = HookshotLoadState.setLoaded(loaded.metadataJson(), false);

        assertTrue(loaded.accepted());
        assertTrue(unloaded.accepted());
        assertNull(unloaded.metadataJson());
        assertFalse(HookshotLoadState.isLoaded(unloaded.metadataJson()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 不正な既存metadataは上書きせず、装填完了を確定しない。
     */
    @Test
    void rejectsMalformedMetadata() {
        HookshotLoadState.Update update = HookshotLoadState.setLoaded("not-json", true);

        assertFalse(update.accepted());
        assertFalse(HookshotLoadState.isLoaded("not-json"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 既存の hookshot.loaded が boolean 以外なら、装填完了による状態変更とフック消費を確定しない。
     */
    @Test
    void rejectsNonBooleanExistingLoadedValues() {
        for (String invalidValue : List.of("\"true\"", "1", "null", "[]", "{}")) {
            HookshotLoadState.Update update = HookshotLoadState.setLoaded(
                "{\"hookshot\":{\"loaded\":" + invalidValue + "}}",
                true
            );

            assertFalse(update.accepted(), "loaded=" + invalidValue);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: boolean の既存 loaded 値は正当な状態として更新できる。
     */
    @Test
    void updatesExistingBooleanLoadedValue() {
        HookshotLoadState.Update update = HookshotLoadState.setLoaded(
            "{\"hookshot\":{\"loaded\":false}}",
            true
        );

        assertTrue(update.accepted());
        assertTrue(HookshotLoadState.isLoaded(update.metadataJson()));
    }
}
