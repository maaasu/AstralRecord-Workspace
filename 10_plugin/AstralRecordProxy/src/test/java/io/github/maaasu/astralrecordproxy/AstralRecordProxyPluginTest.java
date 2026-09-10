package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## Discordサーバー接続通知
     * 検証契約: 初回Proxy接続と通常backend切替を別のDiscord通知文として生成する。
     */
    @Test
    void lifecycleMessagesDistinguishNetworkJoinAndBackendTransfer() throws Exception {
        ProxyConfig config = loadConfig(
            "channelNames:\n  lobby: ロビー\n  ch1: チャンネル1\n");

        assertEquals("Playerさんがサーバーに参加しました",
            AstralRecordProxyPlugin.lifecycleMessage("Player", null, "lobby", config));
        assertEquals("Playerさんがロビーからチャンネル1へ接続しました",
            AstralRecordProxyPlugin.lifecycleMessage("Player", "lobby", "ch1", config));
        assertEquals("Playerさんがサーバーから退出しました",
            AstralRecordProxyPlugin.disconnectMessage("Player", "ch1", config));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## Discordサーバー接続通知
     * 検証契約: 切替元・切替先・切断元のいずれかが除外backendならライフサイクル通知を生成しない。
     */
    @Test
    void excludedBackendSuppressesTransfersAndDisconnects() throws Exception {
        ProxyConfig config = loadConfig(
            "discord:\n  excludedSourceServers:\n    - dev\n");

        assertNull(AstralRecordProxyPlugin.lifecycleMessage("Player", "lobby", "dev", config));
        assertNull(AstralRecordProxyPlugin.lifecycleMessage("Player", "dev", "lobby", config));
        assertNull(AstralRecordProxyPlugin.disconnectMessage("Player", "dev", config));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 全体チャット
     * 検証契約: Proxy経由でも変換前本文と灰色の括弧、金色斜体の変換後本文を維持する。
     */
    @Test
    void proxyChatBodyKeepsOriginalAndConvertedTextStyles() {
        Component body = AstralRecordProxyPlugin.chatBodyComponent("gakkou", "学校");

        assertEquals("gakkou[学校]", PlainTextComponentSerializer.plainText().serialize(body));
        Component converted = findText(body, "学校");
        Component bracket = findText(body, "[");
        assertNotNull(converted);
        assertNotNull(bracket);
        assertEquals(NamedTextColor.GOLD, converted.style().color());
        assertEquals(TextDecoration.State.TRUE, converted.style().decoration(TextDecoration.ITALIC));
        assertEquals(NamedTextColor.GRAY, bracket.style().color());
    }

    private ProxyConfig loadConfig(String content) throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), content, StandardCharsets.UTF_8);
        return ProxyConfig.load(dataDirectory);
    }

    private Component findText(Component component, String text) {
        if (component instanceof TextComponent textComponent && text.equals(textComponent.content())) return component;
        for (Component child : component.children()) {
            Component found = findText(child, text);
            if (found != null) return found;
        }
        return null;
    }
}
