package io.github.maaasu.astralrecordproxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

class ProxyTabDisplayTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ONLINE_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DISCONNECTED_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void rendersBrandCurrentBackendMsptPingAndTotalPlayers() {
        ProxyTabDisplay.HeaderFooter display = ProxyTabDisplay.render(48L, 12.34D, 83);

        assertEquals(Component.text()
            .append(Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text("MSPT ", NamedTextColor.GRAY))
            .append(Component.text("12.3", NamedTextColor.GREEN))
            .build(), display.header());
        assertEquals(Component.text()
            .append(Component.text("通信遅延 ", NamedTextColor.GRAY))
            .append(Component.text("48ms", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("総参加人数 ", NamedTextColor.GRAY))
            .append(Component.text("83人", NamedTextColor.AQUA))
            .build(), display.footer());
    }

    @Test
    void showsMeasuringUntilBackendMetricsArrive() {
        ProxyTabDisplay.HeaderFooter display = ProxyTabDisplay.render(-1L, null, -1);

        assertEquals(Component.text()
            .append(Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text("MSPT ", NamedTextColor.GRAY))
            .append(Component.text("計測中", NamedTextColor.GRAY))
            .build(), display.header());
        assertEquals(Component.text()
            .append(Component.text("通信遅延 ", NamedTextColor.GRAY))
            .append(Component.text("0ms", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("総参加人数 ", NamedTextColor.GRAY))
            .append(Component.text("0人", NamedTextColor.AQUA))
            .build(), display.footer());
    }

    @Test
    void expiresBackendMsptAfterFifteenSeconds() {
        long receivedAt = 1_000L;
        AstralRecordProxyPlugin.ServerMetric metric =
            new AstralRecordProxyPlugin.ServerMetric(18.5D, receivedAt);

        assertEquals(18.5D, AstralRecordProxyPlugin.resolveServerMspt(
            metric, receivedAt + AstralRecordProxyPlugin.SERVER_METRICS_TTL_NANOS));
        assertNull(AstralRecordProxyPlugin.resolveServerMspt(
            metric, receivedAt + AstralRecordProxyPlugin.SERVER_METRICS_TTL_NANOS + 1L));
        assertNull(AstralRecordProxyPlugin.resolveServerMspt(null, receivedAt));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: Proxyの通常TabエントリはRPG側と同じクラス名・レベル順序と色で表示する。
     */
    @Test
    void rendersRpgStyleTabEntry() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", 4, "§dMAG", false);

        Component classTag = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("MAG", NamedTextColor.LIGHT_PURPLE))
            .append(Component.text(" Lv.", NamedTextColor.GRAY))
            .append(Component.text("4", NamedTextColor.YELLOW))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
            .append(classTag)
            .append(Component.text("account#0", NamedTextColor.WHITE)),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: ProxyのAFK中TabエントリはRPG側と同じ赤いAFK接頭辞を表示する。
     */
    @Test
    void rendersRedAfkPrefixInTabEntry() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", 4, "§c§lADM", true);

        Component classTag = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("ADM", NamedTextColor.RED, TextDecoration.BOLD))
            .append(Component.text(" Lv.", NamedTextColor.GRAY))
            .append(Component.text("4", NamedTextColor.YELLOW))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
            .append(classTag)
            .append(Component.text("[AFK] ", NamedTextColor.RED))
            .append(Component.text("account#0", NamedTextColor.WHITE)),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: クラスメタデータ未設定時はRPGのフォールバックとしてMCIDだけを表示する。
     */
    @Test
    void rendersMcidWhenClassMetadataIsUnavailable() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", null, null, false);

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
            .append(Component.text("test-account", NamedTextColor.WHITE)),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: Tabメタデータは現在接続先backendからの通知だけを採用する。
     */
    @Test
    void acceptsTabMetadataOnlyFromCurrentBackend() {
        assertTrue(AstralRecordProxyPlugin.isCurrentBackend("rpg-1", "RPG-1"));
        assertFalse(AstralRecordProxyPlugin.isCurrentBackend("rpg-1", "rpg-2"));
        assertFalse(AstralRecordProxyPlugin.isCurrentBackend(null, "rpg-1"));
    }

    @Test
    void removesTabEntriesForPlayersNoLongerConnectedToProxy() {
        List<UUID> removedIds = new ArrayList<>();
        TabList tabList = recordingTabList(
            removedIds, tabEntry(ONLINE_PLAYER_ID), tabEntry(DISCONNECTED_PLAYER_ID));

        AstralRecordProxyPlugin.removeStaleTabEntries(tabList, Set.of(ONLINE_PLAYER_ID));

        assertEquals(List.of(DISCONNECTED_PLAYER_ID), removedIds);
    }

    @Test
    void removesDisconnectedTabEntryFromEveryViewer() {
        List<UUID> removedIds = new ArrayList<>();
        TabList firstViewerTabList = recordingTabList(removedIds);
        TabList secondViewerTabList = recordingTabList(removedIds);

        AstralRecordProxyPlugin.removeTabEntryFromAllViewers(
            List.of(viewer(firstViewerTabList), viewer(secondViewerTabList)), DISCONNECTED_PLAYER_ID);

        assertEquals(List.of(DISCONNECTED_PLAYER_ID, DISCONNECTED_PLAYER_ID), removedIds);
    }

    private static TabList recordingTabList(List<UUID> removedIds, TabListEntry... entries) {
        return (TabList) Proxy.newProxyInstance(
            TabList.class.getClassLoader(),
            new Class<?>[]{TabList.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getEntries" -> List.of(entries);
                case "removeEntry" -> {
                    removedIds.add((UUID) arguments[0]);
                    yield Optional.empty();
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Player viewer(TabList tabList) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getTabList")) {
                    return tabList;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private static TabListEntry tabEntry(UUID playerId) {
        GameProfile profile = new GameProfile(playerId, "player", List.of());
        return (TabListEntry) Proxy.newProxyInstance(
            TabListEntry.class.getClassLoader(),
            new Class<?>[]{TabListEntry.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getProfile")) {
                    return profile;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
