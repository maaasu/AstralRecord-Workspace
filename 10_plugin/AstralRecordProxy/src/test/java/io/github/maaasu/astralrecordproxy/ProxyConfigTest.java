package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProxyConfigTest {
    @TempDir
    Path dataDirectory;

    @Test
    void allowInsecureTlsDefaultsToFalse() throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), "api:\n  baseUrl: https://localhost:7296\n", StandardCharsets.UTF_8);

        ProxyConfig config = ProxyConfig.load(dataDirectory);

        assertFalse(config.allowInsecureTls());
    }

    @Test
    void allowInsecureTlsCanBeEnabledExplicitly() throws Exception {
        Files.writeString(
            dataDirectory.resolve("config.yml"),
            "api:\n  baseUrl: https://localhost:7296\n  allowInsecureTls: true\n",
            StandardCharsets.UTF_8);

        ProxyConfig config = ProxyConfig.load(dataDirectory);

        assertTrue(config.allowInsecureTls());
    }
}
