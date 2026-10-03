package io.github.maaasu.astralRecord.feature.item.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnhanceCommandArgumentsTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 11. enhance コマンド
     * 検証契約: 空白と現在強化値を含む装備指定を保持して、末尾の値指定を分離する。
     */
    @Test
    void preservesMultiwordSelectionAndRelativeSign() {
        var result = EnhanceCommand.parseArguments(new String[] {"[8]", "Practice", "Sword", "+3", "-2"});
        assertEquals("[8] Practice Sword +3", result.selection());
        assertEquals("-2", result.levelInput());
        assertNull(result.targetName());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 11. enhance コマンド
     * 検証契約: 末尾に対象プレイヤーを指定した場合は、その直前の整数を設定値として分離する。
     */
    @Test
    void separatesTrailingPlayerFromAbsoluteLevel() {
        var result = EnhanceCommand.parseArguments(new String[] {"[8]", "Practice", "Sword", "5", "Alex"});
        assertEquals("[8] Practice Sword", result.selection());
        assertEquals("5", result.levelInput());
        assertEquals("Alex", result.targetName());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 11. enhance コマンド
     * 検証契約: 直前が強化値で末尾がオンライン名なら、数字だけの名前も対象指定として解釈する。
     */
    @Test
    void resolvesNumericOnlinePlayerNameAsTarget() {
        var result = EnhanceCommand.parseArguments(
            new String[] {"[8]", "Practice", "Sword", "+3", "-2", "12345"},
            name -> name.equals("12345")
        );
        assertEquals("[8] Practice Sword +3", result.selection());
        assertEquals("-2", result.levelInput());
        assertEquals("12345", result.targetName());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 11. enhance コマンド
     * 検証契約: 数値の直前が強化値でない場合とオンライン名に一致しない場合は、その数値を設定値とする。
     */
    @Test
    void keepsNumericLevelUnlessAValidTrailingTargetIsPresent() {
        var noPreviousLevel = EnhanceCommand.parseArguments(
            new String[] {"[8]", "Sword", "12345"}, name -> name.equals("12345"));
        assertEquals("12345", noPreviousLevel.levelInput());
        assertNull(noPreviousLevel.targetName());
        var noMatchingPlayer = EnhanceCommand.parseArguments(
            new String[] {"[8]", "Sword", "+3", "12345"}, name -> false);
        assertEquals("[8] Sword +3", noMatchingPlayer.selection());
        assertEquals("12345", noMatchingPlayer.levelInput());
        assertNull(noMatchingPlayer.targetName());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 11. enhance コマンド
     * 検証契約: 装備指定・値指定の不足、小数、符号だけ、不正な整数は解釈しない。
     */
    @Test
    void rejectsMissingOrMalformedArguments() {
        assertNull(EnhanceCommand.parseArguments(new String[0]));
        assertNull(EnhanceCommand.parseArguments(new String[] {"5"}));
        for (String invalid : List.of("+", "-", "1.5", "1e3", "++1", "bad")) {
            assertNull(EnhanceCommand.parseArguments(new String[] {"Sword", invalid}));
            assertNull(EnhanceCommand.parseArguments(new String[] {"Sword", invalid, "Alex"}));
        }
    }
}
