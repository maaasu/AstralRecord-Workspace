package io.github.maaasu.astralRecord.feature.player.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RomajiKanaConverterTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 2. メッセージサービス > ### ローマ字チャット変換
     * 検証契約: 促音・拗音・撥音を含むローマ字本文を、外部通信前のひらがなへ変換する。
     */
    @Test
    void convertsRomajiWithSokuonYouonAndNasalNToHiragana() {
        assertEquals("がっこう にゅうがく あんない", RomajiKanaConverter.convert("gakkou nyuugaku annai"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 2. メッセージサービス > ### ローマ字チャット変換
     * 検証契約: ローマ字以外の記号と日本語文字は変換せずに保持する。
     */
    @Test
    void preservesNonRomajiCharacters() {
        assertEquals("こんにちは! 123", RomajiKanaConverter.convert("こんにちは! 123"));
    }
}
