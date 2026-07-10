package io.github.maaasu.astralRecord.feature.player.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedChatEventHandlerTest {

    @Test
    void blocksVanillaDirectMessageAliasesWithOrWithoutNamespace() {
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/msg player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/minecraft:tell player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/bukkit:w player hello"));
        assertTrue(ManagedChatEventHandler.isVanillaDirectMessageCommand("/minecraft:whisper player hello"));
    }

    @Test
    void keepsManagedMessageCommandAvailable() {
        assertFalse(ManagedChatEventHandler.isVanillaDirectMessageCommand("/message player hello"));
        assertFalse(ManagedChatEventHandler.isVanillaDirectMessageCommand("/party message hello"));
    }
}
