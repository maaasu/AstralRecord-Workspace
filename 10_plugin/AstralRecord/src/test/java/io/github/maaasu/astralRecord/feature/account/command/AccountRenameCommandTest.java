package io.github.maaasu.astralRecord.feature.account.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRenameCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント名変更
     * 検証契約: rename はASCII英数字3〜50文字だけを受理する。
     */
    @Test
    void acceptsOnlyThreeToFiftyAsciiAlphanumericCharacters() {
        assertTrue(AccountRenameCommand.isValidAccountName("Ab3"));
        assertTrue(AccountRenameCommand.isValidAccountName("a".repeat(50)));
        assertFalse(AccountRenameCommand.isValidAccountName("ab"));
        assertFalse(AccountRenameCommand.isValidAccountName("a".repeat(51)));
        assertFalse(AccountRenameCommand.isValidAccountName("name_1"));
        assertFalse(AccountRenameCommand.isValidAccountName("名前123"));
    }
}
