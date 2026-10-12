package io.github.maaasu.astralrecordproxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.Modifier;
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
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ONLINE_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DISCONNECTED_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: Tab同期はスナップショット取得からentry反映まで直列実行する。
     */
    @Test
    void serializesTabRefreshes() throws Exception {
        var method = AstralRecordProxyPlugin.class.getDeclaredMethod("refreshTabEntries");

        assertTrue(Modifier.isSynchronized(method.getModifiers()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: 切断時のentry削除はTab同期と同じ排他境界で直列実行する。
     */
    @Test
    void serializesDisconnectCleanupWithTabRefresh() throws Exception {
        var method = AstralRecordProxyPlugin.class.getDeclaredMethod(
            "removeDisconnectedTabEntry", UUID.class);

        assertTrue(Modifier.isSynchronized(method.getModifiers()));
    }

    @Test
    void rendersBrandCurrentBackendMsptPingAndTotalPlayers() {
        ProxyTabDisplay.HeaderFooter display = ProxyTabDisplay.render(
            "mc.astralrecord.com", "第一チャンネル", 12, 48L, 12.34D, 83);

        assertEquals("✦  A S T R A L   R E C O R D  ✦\n[ mc.astralrecord.com ]",
            PLAIN.serialize(display.header()));
        assertEquals("[ CHANNEL: 第一チャンネル ]\n[ ONLINE: 12  |  TOTAL: 83 ]"
                + "\n[ MSPT: 12.3  |  PING: 48ms ]",
            PLAIN.serialize(display.footer()));
    }

    @Test
    void showsMeasuringUntilBackendMetricsArrive() {
        ProxyTabDisplay.HeaderFooter display = ProxyTabDisplay.render("", null, -1, -1L, null, -1);

        assertEquals("✦  A S T R A L   R E C O R D  ✦", PLAIN.serialize(display.header()));
        assertEquals("[ CHANNEL: CONNECTING ]\n[ ONLINE: 0  |  TOTAL: 0 ]"
                + "\n[ MSPT: MEASURING  |  PING: 0ms ]",
            PLAIN.serialize(display.footer()));
    }

    @Test
    void changesTitleShimmerWithoutChangingDisplayedInformation() {
        ProxyTabDisplay.HeaderFooter first = ProxyTabDisplay.render(
            "mc.astralrecord.com", "第一チャンネル", 12, 48L, 12.34D, 83, List.of(), 0);
        ProxyTabDisplay.HeaderFooter next = ProxyTabDisplay.render(
            "mc.astralrecord.com", "第一チャンネル", 12, 48L, 12.34D, 83, List.of(), 1);

        assertEquals(PLAIN.serialize(first.header()), PLAIN.serialize(next.header()));
        assertEquals(PLAIN.serialize(first.footer()), PLAIN.serialize(next.footer()));
        assertFalse(first.header().equals(next.header()));
        assertEquals(first.footer(), next.footer());
    }

    @Test
    void animatesOnlyAstralderAccountNameAndKeepsSlotPlain() {
        PlayerMetadata astralder = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "astral#2", 4, "§dMAG", false, 0, false, false,
            VipTier.ASTRALDER, 35);
        PlayerMetadata doner = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "doner#2", 4, "§dMAG", false, 0, false, false,
            VipTier.DONER);

        Component first = AstralRecordProxyPlugin.tabEntryDisplayName(astralder, 0);
        Component next = AstralRecordProxyPlugin.tabEntryDisplayName(astralder, 1);
        assertEquals("[rpg] [MAG CLV.4] astral#2 [PL: test-account | PLV: 35]", PLAIN.serialize(first));
        assertEquals("[rpg] [MAG Lv.4] astral#2",
            PLAIN.serialize(AstralRecordProxyPlugin.tabDisplayName(astralder, 1)));
        assertEquals(PLAIN.serialize(first), PLAIN.serialize(next));
        assertFalse(first.equals(next));
        assertEquals(AstralRecordProxyPlugin.tabEntryDisplayName(doner, 0),
            AstralRecordProxyPlugin.tabEntryDisplayName(doner, 1));
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
     * 検証契約: Proxyのチャット用ラベルは従来のクラス名・Lv.順序と色で表示する。
     */
    @Test
    void rendersRpgStyleChatLabel() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", 4, "§dMAG", false, 0, false, false);

        Component classTag = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("MAG", NamedTextColor.LIGHT_PURPLE))
            .append(Component.text(" Lv.", NamedTextColor.GRAY))
            .append(Component.text("4", NamedTextColor.YELLOW))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
            .append(classTag)
            .append(Component.text("account", NamedTextColor.WHITE)
                .append(Component.text("#0", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: チャットでは最大クラスレベル到達者のLv.を維持し、数値の代わりに赤太字のmaxを表示する。
     */
    @Test
    void rendersMaximumClassLevelInChatLabel() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", 100, "§dMAG", false, 0, true, false);

        Component classTag = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("MAG", NamedTextColor.LIGHT_PURPLE))
            .append(Component.text(" Lv.", NamedTextColor.GRAY))
            .append(Component.text("max", NamedTextColor.RED, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
                .append(classTag)
                .append(Component.text("account", NamedTextColor.WHITE)
                    .append(Component.text("#0", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    @Test
    void rendersDonerAccountNameInAqua() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", 4, "§dMAG", false, 0, false, false, VipTier.DONER);

        Component classTag = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("MAG", NamedTextColor.LIGHT_PURPLE))
            .append(Component.text(" Lv.", NamedTextColor.GRAY))
            .append(Component.text("4", NamedTextColor.YELLOW))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
            .append(classTag)
            .append(Component.text("account", NamedTextColor.AQUA)
                .append(Component.text("#0", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    @Test
    void rendersAdminAccountNameInGrayWithoutDonorDecoration() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", 4, "§dMAG", false, 99, false, false);

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
            .append(Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("MAG", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" Lv.", NamedTextColor.GRAY))
                .append(Component.text("4", NamedTextColor.YELLOW))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY)))
            .append(Component.text("account", NamedTextColor.WHITE)
                .append(Component.text("#0", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: ProxyのAFK中チャットラベルはRPG側と同じ赤いAFK接頭辞を表示する。
     */
    @Test
    void rendersRedAfkPrefixInChatLabel() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", 4, "§c§lADM", true, 0, false, false);

        Component classTag = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("ADM", NamedTextColor.RED, TextDecoration.BOLD))
            .append(Component.text(" Lv.", NamedTextColor.GRAY))
            .append(Component.text("4", NamedTextColor.YELLOW))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
            .append(classTag)
            .append(Component.text("[AFK] ", NamedTextColor.RED))
            .append(Component.text("account", NamedTextColor.WHITE)
                .append(Component.text("#0", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: クラスメタデータ未設定時はチャットのフォールバックとしてMCIDだけを表示する。
     */
    @Test
    void rendersMcidWhenClassMetadataIsUnavailableInChat() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-account", "rpg-1", "rpg", "account#0", null, null, false, 0, false, false);

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
            .append(Component.text("test-account", NamedTextColor.WHITE)),
            AstralRecordProxyPlugin.tabDisplayName(metadata));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: チャットのADMINアカウントはchannelとアカウント名を表示し、クラス・レベル・AFKを省略する。
     */
    @Test
    void rendersChannelAndAccountNameForAdminAccountModeInChat() {
        PlayerMetadata metadata = new PlayerMetadata(
            PLAYER_ID, "test-admin", "rpg-1", "rpg", "admin#1", 100, "§c§lADM", true, 0, true, true);

        assertEquals(Component.text("[rpg] ", NamedTextColor.GRAY)
                .append(Component.text("admin", NamedTextColor.WHITE)
                    .append(Component.text("#1", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))),
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体Tabと所在
     * 検証契約: チャットの送信元と表示名が一致しない移動・切替後のメタデータを流用しない。
     */
    @Test
    void rejectsChatMetadataFromAnotherBackendOrAccount() {
        PlayerMetadata value = new PlayerMetadata(PLAYER_ID, "player", "rpg-2", "channel-2",
            "current#1", 10, "class", false, 0, false, false);
        assertEquals(value, AstralRecordProxyPlugin.chatMetadata(value, "RPG-2", "current#1"));
        assertNull(AstralRecordProxyPlugin.chatMetadata(value, "rpg-1", "current#1"));
        assertNull(AstralRecordProxyPlugin.chatMetadata(value, "rpg-2", "previous#0"));
        assertNull(AstralRecordProxyPlugin.chatMetadata(null, "rpg-2", "current#1"));
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
