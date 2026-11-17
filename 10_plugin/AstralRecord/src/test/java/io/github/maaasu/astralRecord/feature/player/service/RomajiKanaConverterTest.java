package io.github.maaasu.astralRecord.feature.player.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RomajiKanaConverterTest {

    /**
     * 促音・拗音・撥音を含むローマ字本文を、外部通信前のひらがなへ変換する。
     */
    @Test
    void convertsRomajiWithSokuonYouonAndNasalNToHiragana() {
        assertEquals("がっこう にゅうがく あんない", RomajiKanaConverter.convert("gakkou nyuugaku annai"));
    }

    /**
     * ローマ字以外の記号と日本語文字は変換せずに保持する。
     */
    @Test
    void preservesNonRomajiCharacters() {
        assertEquals("こんにちは! 123", RomajiKanaConverter.convert("こんにちは! 123"));
    }
}
