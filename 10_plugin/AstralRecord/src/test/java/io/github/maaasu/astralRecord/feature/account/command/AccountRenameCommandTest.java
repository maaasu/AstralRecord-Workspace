package io.github.maaasu.astralRecord.feature.account.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRenameCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント名変更
     * 検証契約: Java 版は ASCII 英数字またはアンダースコアを3〜50文字、Bedrock 版は ASCII 英字またはアンダースコアを3〜49文字受理する。ピリオドの入力は拒否する。
     */
    @Test
    void validatesRenameInputForThePlayersEdition() {
        assertTrue(AccountRenameCommand.isValidAccountName("Ab3", false));
        assertTrue(AccountRenameCommand.isValidAccountName("name_1", false));
        assertTrue(AccountRenameCommand.isValidAccountName("a".repeat(50), false));
        assertFalse(AccountRenameCommand.isValidAccountName("ab", false));
        assertFalse(AccountRenameCommand.isValidAccountName("a".repeat(51), false));
        assertFalse(AccountRenameCommand.isValidAccountName("name-1", false));
        assertFalse(AccountRenameCommand.isValidAccountName("名前123", false));
        assertFalse(AccountRenameCommand.isValidAccountName(".Name", false));

        assertTrue(AccountRenameCommand.isValidAccountName("Ab_", true));
        assertTrue(AccountRenameCommand.isValidAccountName("a".repeat(49), true));
        assertFalse(AccountRenameCommand.isValidAccountName("a".repeat(50), true));
        assertFalse(AccountRenameCommand.isValidAccountName("name_1", true));
        assertFalse(AccountRenameCommand.isValidAccountName(".Name", true));
        assertFalse(AccountRenameCommand.isValidAccountName("Name.Other", true));
    }
}
