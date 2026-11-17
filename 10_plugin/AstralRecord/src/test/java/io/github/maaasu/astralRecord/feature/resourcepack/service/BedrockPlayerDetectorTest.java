package io.github.maaasu.astralRecord.feature.resourcepack.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPlayerDetectorTest {
    /**
     * 非空白prefixのcase-sensitive先頭一致だけでBedrockを判定し、player cacheへ依存しない。
     */
    @Test
    void detectsConfiguredPrefixWithoutPlayerCache() {
        assertTrue(BedrockPlayerDetector.isBedrock(".BedrockUser", List.of(".", "*")));
        assertTrue(BedrockPlayerDetector.isBedrock("*ConsoleUser", List.of(".", "*")));
        assertFalse(BedrockPlayerDetector.isBedrock("JavaUser", List.of(".", "*")));
        assertFalse(BedrockPlayerDetector.isBedrock(".BedrockUser", List.of("", "  ")));
    }

    /**
     * user.mcidにドットを含む場合だけ、設定値やプレイヤーキャッシュに依存せずBedrock判定をtrueにする。
     */
    @Test
    void detectsBedrockFromMcidDot() {
        assertTrue(BedrockPlayerDetector.isBedrockMcid("Bedrock.User"));
        assertTrue(BedrockPlayerDetector.isBedrockMcid(".BedrockUser"));
        assertFalse(BedrockPlayerDetector.isBedrockMcid("JavaUser"));
        assertFalse(BedrockPlayerDetector.isBedrockMcid(null));
    }
}
