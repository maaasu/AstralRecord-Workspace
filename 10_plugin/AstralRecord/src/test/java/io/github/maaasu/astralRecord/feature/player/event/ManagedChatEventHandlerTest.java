package io.github.maaasu.astralRecord.feature.player.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedChatEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラDMコマンド遮断
     * 検証契約: msg/tell/w/whisperをnamespace有無にかかわらず遮断する。
     */
    @Test
    void blocksVanillaDirectMessageAliasesWithOrWithoutNamespace() {
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/msg player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/minecraft:tell player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/bukkit:w player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/minecraft:whisper player hello"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラDMコマンド遮断
     * 検証契約: AstralRecord管理のmessageコマンドは遮断対象にしない。
     */
    @Test
    void keepsManagedMessageCommandAvailable() {
        assertFalse(ManagedChatEventHandler.isVanillaDirectMessageCommand("/message player hello"));
        assertFalse(ManagedChatEventHandler.isVanillaDirectMessageCommand("/party message hello"));
    }
}
