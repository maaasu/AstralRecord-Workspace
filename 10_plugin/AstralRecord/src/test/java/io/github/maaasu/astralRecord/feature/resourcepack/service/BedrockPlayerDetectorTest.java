package io.github.maaasu.astralRecord.feature.resourcepack.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPlayerDetectorTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/30-resource-pack/30_1-モデル定義.md
     * 章・見出し: # 30_1-モデル定義 > ## 4. Bedrock 判定
     * 検証契約: 非空白prefixのcase-sensitive先頭一致だけでBedrockを判定し、player cacheへ依存しない。
     */
    @Test
    void detectsConfiguredPrefixWithoutPlayerCache() {
        assertTrue(BedrockPlayerDetector.isBedrock(".BedrockUser", List.of(".", "*")));
        assertTrue(BedrockPlayerDetector.isBedrock("*ConsoleUser", List.of(".", "*")));
        assertFalse(BedrockPlayerDetector.isBedrock("JavaUser", List.of(".", "*")));
        assertFalse(BedrockPlayerDetector.isBedrock(".BedrockUser", List.of("", "  ")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 2. プレイヤーセッション > ### 2.3 Bedrock 判定
     * 検証契約: user.mcidにドットを含む場合だけ、設定値やプレイヤーキャッシュに依存せずBedrock判定をtrueにする。
     */
    @Test
    void detectsBedrockFromMcidDot() {
        assertTrue(BedrockPlayerDetector.isBedrockMcid("Bedrock.User"));
        assertTrue(BedrockPlayerDetector.isBedrockMcid(".BedrockUser"));
        assertFalse(BedrockPlayerDetector.isBedrockMcid("JavaUser"));
        assertFalse(BedrockPlayerDetector.isBedrockMcid(null));
    }
}
