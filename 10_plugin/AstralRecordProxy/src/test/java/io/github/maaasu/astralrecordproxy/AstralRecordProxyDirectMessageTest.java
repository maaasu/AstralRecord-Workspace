package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            "https://example.invalid", "api-key", "sync-key", 3000, 500L, true,
            List.of(), Set.of(authorityId)));

        plugin.deliverDirectMessage("ch1", sender, new BackendProtocol.DirectMessage(
            sender.getUniqueId(), "target", "sender#0", 7, "gakkou", "学校"));

        assertEquals(1, senderMessages.get());
        assertEquals(1, targetMessages.get());
        assertEquals(1, authorityMessages.get());
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
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Proxy configuration setup failed", exception);
        }
    }
}
