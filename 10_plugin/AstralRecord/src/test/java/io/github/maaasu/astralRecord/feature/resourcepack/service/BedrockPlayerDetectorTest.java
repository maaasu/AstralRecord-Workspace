package io.github.maaasu.astralRecord.feature.resourcepack.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPlayerDetectorTest {
    @Test
    void detectsConfiguredPrefixWithoutPlayerCache() {
        assertTrue(BedrockPlayerDetector.isBedrock(".BedrockUser", List.of(".", "*")));
        assertTrue(BedrockPlayerDetector.isBedrock("*ConsoleUser", List.of(".", "*")));
        assertFalse(BedrockPlayerDetector.isBedrock("JavaUser", List.of(".", "*")));
        assertFalse(BedrockPlayerDetector.isBedrock(".BedrockUser", List.of("", "  ")));
    }
}
