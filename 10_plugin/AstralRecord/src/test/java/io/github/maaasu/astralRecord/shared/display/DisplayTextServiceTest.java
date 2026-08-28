package io.github.maaasu.astralRecord.shared.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayTextServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_5-例外・ログ・運用.md
     * 章・見出し: # 14_5-例外・ログ・運用 > ## 5. 表示負荷
     * 検証契約: HP回復の浮遊数値は黄緑色のプラス接頭辞と整数表示を使う。
     */
    @Test
    void healingNumberUsesLimePlusPrefixAndRoundedAmount() {
        assertEquals("&a+13", DisplayTextService.floatingNumberText("&a+", 12.5D));
    }
}
