package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
     * 検証契約: RPG間の接続要求はProxy最高権限UUIDだけに許可する。
     */
    @Test
    void onlyServerAuthorityCanRequestGameServerFromAnotherGameServer() throws Exception {
        UUID authority = UUID.fromString("7c72cb6c-8cfd-4d74-8c67-6f39a4b4b9ca");
        ProxyConfig config = loadConfig("serverAuthorityUsers:\n  - " + authority + "\n");

        assertEquals(true,
            AstralRecordProxyPlugin.canRequestGameServerFrom("ch1", config, authority, true));
        assertEquals(false,
            AstralRecordProxyPlugin.canRequestGameServerFrom("ch1", config, authority, false));
        assertEquals(false,
            AstralRecordProxyPlugin.canRequestGameServerFrom("ch1", config, UUID.randomUUID(), true));
        assertEquals(true,
            AstralRecordProxyPlugin.canRequestGameServerFrom("lobby", config, UUID.randomUUID(), false));
        assertEquals(false, AstralRecordProxyPlugin.shouldRejectCurrentGameConnection(true, true));
        assertEquals(true, AstralRecordProxyPlugin.shouldRejectCurrentGameConnection(true, false));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 検証契約: Proxy最高権限の/server候補も現在地を除くRPG channelだけに限定する。
     */
    @Test
    void serverCommandSuggestionsAreRestrictedToAuthorityGameServers() throws Exception {
        UUID authority = UUID.fromString("7c72cb6c-8cfd-4d74-8c67-6f39a4b4b9ca");
        ProxyConfig config = loadConfig(
            "gameServers:\n  - ch1\n  - ch2\n  - dev\nserverAuthorityUsers:\n  - " + authority + "\n");

        assertEquals(List.of("ch2"),
            AstralRecordProxyPlugin.serverCommandSuggestions(config, authority, "ch1", "ch"));
        assertEquals(List.of(),
            AstralRecordProxyPlugin.serverCommandSuggestions(config, UUID.randomUUID(), "ch1", "ch"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 検証契約: 高速なRPG再接続は設定済み30秒クールタイムの残秒を返す。
     */
    @Test
    void directServerCommandUsesTransferCooldownWindow() {
        assertEquals(30L, AstralRecordProxyPlugin.cooldownRemainingSeconds(1_000L, 1_000L, 30L));
        assertEquals(1L, AstralRecordProxyPlugin.cooldownRemainingSeconds(1_000L, 30_001L, 30L));
        assertEquals(0L, AstralRecordProxyPlugin.cooldownRemainingSeconds(1_000L, 31_000L, 30L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 検証契約: RPG間転送準備は接続元・接続先・10秒の有効期限が一致する場合だけ利用できる。
     */
    @Test
    void authorityTransferPreparationRequiresMatchingRouteAndLifetime() {
        var preparation = new AstralRecordProxyPlugin.AuthorityTransferPreparation("ch1", "ch2", 11_000L);

        assertEquals(true, AstralRecordProxyPlugin.matchesAuthorityTransferPreparation(
            preparation, "CH1", "CH2", 11_000L));
        assertEquals(false, AstralRecordProxyPlugin.matchesAuthorityTransferPreparation(
            preparation, "ch1", "dev", 11_000L));
        assertEquals(false, AstralRecordProxyPlugin.matchesAuthorityTransferPreparation(
            preparation, "ch1", "ch2", 11_001L));
    }


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

    /** Management DBの無期限BANは理由を含めて切断理由へ表示する。 */
    @Test
    void indefiniteBanDisconnectReasonIncludesReason() {
        Component reason = AstralRecordProxyPlugin.banDisconnectReason(
            true, null, "迷惑行為", OffsetDateTime.parse("2026-09-16T00:00:00Z"));

        assertEquals("このサーバーへの参加は禁止されています。\nBAN期限: 無期限\n理由: 迷惑行為",
            PlainTextComponentSerializer.plainText().serialize(reason));
    }

    /** Management DBの有期限BANは残日数、解除日時、理由を切断理由へ表示する。 */
    @Test
    void temporaryBanDisconnectReasonIncludesRemainingDaysAndExpiry() {
        Component reason = AstralRecordProxyPlugin.banDisconnectReason(false,
            OffsetDateTime.parse("2026-09-18T00:00:00Z"), "不正利用",
            OffsetDateTime.parse("2026-09-16T12:00:00Z"));

        assertEquals("このサーバーへの参加は禁止されています。\nBAN期限: あと2日\n解除日時: 2026年9月18日 9:00\n理由: 不正利用",
            PlainTextComponentSerializer.plainText().serialize(reason));
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
