package io.github.maaasu.astralRecord.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorCodeUtilTest {

    @Test
    void toLegacyTextTranslatesMasterDisplayColorCodes() {
        assertEquals("§e新規マスタ", ColorCodeUtil.toLegacyText("&e新規マスタ", "fallback"));
    }

    @Test
    void toPlainTextStripsLowerAndUpperLegacyColorCodes() {
        assertEquals("新規マスタ", ColorCodeUtil.toPlainText("&E新規&lマスタ", "fallback"));
    }

    @Test
    void toLegacyTextUsesFallbackForBlankDisplayName() {
        assertEquals("fallback", ColorCodeUtil.toLegacyText(" ", "fallback"));
    }
}
