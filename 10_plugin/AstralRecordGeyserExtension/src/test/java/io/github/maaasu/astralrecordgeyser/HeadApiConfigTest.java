package io.github.maaasu.astralrecordgeyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadApiConfigTest {
    @TempDir
    Path dataDirectory;

    @Test
    void readsAllowInsecureTlsWhenEnabled() throws Exception {
        writeConfig("""
            api:
              baseUrl: "https://localhost:444"
              apiKey: "test-key"
              timeoutSeconds: 15
              allowInsecureTls: true
            """);

        HeadApiConfig config = HeadApiConfig.load(dataDirectory);

        assertTrue(config.allowInsecureTls);
    }

    @Test
    void defaultsAllowInsecureTlsToFalse() throws Exception {
        writeConfig("""
            api:
              baseUrl: "https://localhost:444"
              apiKey: "test-key"
              timeoutSeconds: 15
            """);

        HeadApiConfig config = HeadApiConfig.load(dataDirectory);

        assertFalse(config.allowInsecureTls);
    }

    private void writeConfig(String contents) throws Exception {
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("config.yml"), contents, StandardCharsets.UTF_8);
    }
}
