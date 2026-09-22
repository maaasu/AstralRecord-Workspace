package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AstralRecordProxyDirectMessageTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 最高権限とプライベートチャット監視
     * 検証契約: 別backendのDMは送受信者へ一度ずつ配送し、Proxy最高権限ユーザーだけへ監視表示する。
     */
    @Test
    void deliversCrossBackendDirectMessageAndNotifiesOnlyAuthorityUser() {
        AtomicInteger senderMessages = new AtomicInteger();
        AtomicInteger targetMessages = new AtomicInteger();
        AtomicInteger authorityMessages = new AtomicInteger();
        Player sender = player("sender", UUID.randomUUID(), senderMessages);
        Player target = player("target", UUID.randomUUID(), targetMessages);
        UUID authorityId = UUID.randomUUID();
        Player authority = player("authority", authorityId, authorityMessages);
        ProxyServer proxy = proxy(List.of(sender, target, authority));
        AstralRecordProxyPlugin plugin = new AstralRecordProxyPlugin(proxy, null, Path.of("target"));
        setConfig(plugin, new ProxyConfig(
            "lobby", List.of("ch1", "ch2"), Map.of(), Map.of(), 30L, 2L, 10L,
            "https://example.invalid", "api-key", "sync-key", 3000, 500L, 5L, true,
            "mc.astralrecord.com", List.of(), Set.of(authorityId)));

        plugin.deliverDirectMessage("ch1", sender, new BackendProtocol.DirectMessage(
            sender.getUniqueId(), "target", "sender#0", 7, "gakkou", "学校"));

        assertEquals(1, senderMessages.get());
        assertEquals(1, targetMessages.get());
        assertEquals(1, authorityMessages.get());
    }

    @Test
    void doesNotSendMonitorCopyToAuthoritySender() {
        AtomicInteger authorityMessages = new AtomicInteger();
        AtomicInteger targetMessages = new AtomicInteger();
        UUID authorityId = UUID.randomUUID();
        Player authority = player("authority", authorityId, authorityMessages);
        Player target = player("target", UUID.randomUUID(), targetMessages);
        ProxyServer proxy = proxy(List.of(authority, target));
        AstralRecordProxyPlugin plugin = new AstralRecordProxyPlugin(proxy, null, Path.of("target"));
        setConfig(plugin, new ProxyConfig(
            "lobby", List.of("ch1", "ch2"), Map.of(), Map.of(), 30L, 2L, 10L,
            "https://example.invalid", "api-key", "sync-key", 3000, 500L, 5L, true,
            "mc.astralrecord.com", List.of(), Set.of(authorityId)));

        plugin.deliverDirectMessage("ch1", authority, new BackendProtocol.DirectMessage(
            authority.getUniqueId(), "target", "authority#0", 7, "gakkou", "学校"));

        assertEquals(1, authorityMessages.get());
        assertEquals(1, targetMessages.get());
    }

    @Test
    void doesNotSendMonitorCopyToAuthorityTarget() {
        AtomicInteger senderMessages = new AtomicInteger();
        AtomicInteger authorityMessages = new AtomicInteger();
        Player sender = player("sender", UUID.randomUUID(), senderMessages);
        UUID authorityId = UUID.randomUUID();
        Player authority = player("authority", authorityId, authorityMessages);
        ProxyServer proxy = proxy(List.of(sender, authority));
        AstralRecordProxyPlugin plugin = new AstralRecordProxyPlugin(proxy, null, Path.of("target"));
        setConfig(plugin, new ProxyConfig(
            "lobby", List.of("ch1", "ch2"), Map.of(), Map.of(), 30L, 2L, 10L,
            "https://example.invalid", "api-key", "sync-key", 3000, 500L, 5L, true,
            "mc.astralrecord.com", List.of(), Set.of(authorityId)));

        plugin.deliverDirectMessage("ch1", sender, new BackendProtocol.DirectMessage(
            sender.getUniqueId(), "authority", "sender#0", 7, "gakkou", "学校"));

        assertEquals(1, senderMessages.get());
        assertEquals(1, authorityMessages.get());
    }

    @Test
    void identifiesPartyParticipantForMonitorExclusion() {
        UUID senderId = UUID.randomUUID();
        UUID authorityId = UUID.randomUUID();
        Player authority = player("authority", authorityId, new AtomicInteger());
        BackendProtocol.PrivateChat chat = new BackendProtocol.PrivateChat(
            senderId, "party", "sender", "", "party", "message", "message",
            Set.of(senderId, authorityId));

        assertTrue(pluginFor(authority).isPrivateChatParticipant(authority, chat));
    }

    @Test
    void excludesLegacyDirectMonitorCopyUsingAuthorityDisplayName() {
        AtomicInteger authorityMessages = new AtomicInteger();
        UUID authorityId = UUID.randomUUID();
        Player authority = player("authority", authorityId, authorityMessages);
        AstralRecordProxyPlugin plugin = pluginFor(authority);
        setMetadata(plugin, new PlayerMetadata(
            authorityId, "authority", "ch1", "ch1", "authority#0", 0, "", false, 0, false, false));

        plugin.broadcastPrivateChat("ch1", new BackendProtocol.PrivateChat(
            UUID.randomUUID(), "direct", "sender#0", "authority#0", "", "message", "message", Set.of()));

        assertEquals(0, authorityMessages.get());
    }

    private static AstralRecordProxyPlugin pluginFor(Player authority) {
        AstralRecordProxyPlugin plugin = new AstralRecordProxyPlugin(
            proxy(List.of(authority)), null, Path.of("target"));
        setConfig(plugin, new ProxyConfig(
            "lobby", List.of("ch1", "ch2"), Map.of(), Map.of(), 30L, 2L, 10L,
            "https://example.invalid", "api-key", "sync-key", 3000, 500L, 5L, true,
            "mc.astralrecord.com", List.of(), Set.of(authority.getUniqueId())));
        return plugin;
    }

    private static Player player(String username, UUID playerId, AtomicInteger messages) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class}, (ignored, method, arguments) -> {
                return switch (method.getName()) {
                    case "getUsername" -> username;
                    case "getUniqueId" -> playerId;
                    case "sendMessage" -> {
                        messages.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> username;
                    default -> defaultValue(method.getReturnType());
                };
            });
    }

    private static ProxyServer proxy(List<Player> players) {
        return (ProxyServer) Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(), new Class<?>[] {ProxyServer.class}, (ignored, method, arguments) -> {
                if (method.getName().equals("getAllPlayers")) return players;
                return defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }

    private static void setConfig(AstralRecordProxyPlugin plugin, ProxyConfig config) {
        try {
            var field = AstralRecordProxyPlugin.class.getDeclaredField("config");
            field.setAccessible(true);
            field.set(plugin, config);
            var managedField = AstralRecordProxyPlugin.class.getDeclaredField("managedSettings");
            managedField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<ManagedNetworkSettings> managed =
                (java.util.concurrent.atomic.AtomicReference<ManagedNetworkSettings>) managedField.get(plugin);
            managed.set(config.legacySettings());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Proxy configuration setup failed", exception);
        }
    }

    private static void setMetadata(AstralRecordProxyPlugin plugin, PlayerMetadata metadata) {
        try {
            var field = AstralRecordProxyPlugin.class.getDeclaredField("metadata");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, PlayerMetadata> values = (Map<UUID, PlayerMetadata>) field.get(plugin);
            values.put(metadata.playerId(), metadata);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Proxy metadata setup failed", exception);
        }
    }
}
