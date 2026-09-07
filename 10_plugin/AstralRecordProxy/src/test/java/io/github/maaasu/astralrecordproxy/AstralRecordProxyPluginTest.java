package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AstralRecordProxyPluginTest {
    @TempDir
    Path dataDirectory;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 検証契約: 除外backendのチャットもProxy内へ配信し、Discord中継用APIへは登録しない。
     */
    @Test
    void excludedSourceServerKeepsProxyBroadcastButSkipsDiscordRelay() throws Exception {
        ProxyConfig config = loadConfig("discord:\n  excludedSourceServers:\n    - dev\n");
        List<String> deliveries = new ArrayList<>();

        AstralRecordProxyPlugin.dispatchMinecraftChat(
            "dev",
            config,
            () -> deliveries.add("minecraft"),
            () -> deliveries.add("discord"));

        assertEquals(List.of("minecraft"), deliveries);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 検証契約: 除外されていないbackendのチャットはProxy内配信後にDiscord中継用APIへ登録する。
     */
    @Test
    void nonExcludedSourceServerBroadcastsBeforeDiscordRelay() throws Exception {
        ProxyConfig config = loadConfig("api:\n  baseUrl: https://localhost:7296\n");
        List<String> deliveries = new ArrayList<>();

        AstralRecordProxyPlugin.dispatchMinecraftChat(
            "dev",
            config,
            () -> deliveries.add("minecraft"),
            () -> deliveries.add("discord"));

        assertEquals(List.of("minecraft", "discord"), deliveries);
    }

    private ProxyConfig loadConfig(String content) throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), content, StandardCharsets.UTF_8);
        return ProxyConfig.load(dataDirectory);
    }
}
