package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerCapacityServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_2-ユースケース.md
     * 章・見出し: # 03_2-ユースケース > ## 12. 権限別接続人数制限
     * 検証契約: 基本枠30、寄付者追加枠5、管理者追加枠1のとき、通常は30人、寄付者以上は35人、管理者は36人まで接続できる。
     */
    @Test
    void reservesConfiguredSlotsByPermissionLevel() {
        ConfigProperties properties = mockProperties(30, 5, 1);

        try (MockedStatic<ConfigProperties> config = mockStatic(ConfigProperties.class)) {
            config.when(ConfigProperties::getInstance).thenReturn(properties);
            PlayerCapacityService service = new PlayerCapacityService(30);

            assertEquals(30, service.getMaximumPlayersForPermission(UserPermission.PLAYER.getValue()));
            assertEquals(35, service.getMaximumPlayersForPermission(UserPermission.DONOR.getValue()));
            assertEquals(36, service.getMaximumPlayersForPermission(UserPermission.ADMIN.getValue()));

            assertFalse(service.tryReserve(UUID.randomUUID(), UserPermission.PLAYER.getValue()));
            for (int index = 0; index < 5; index++) {
                assertTrue(service.tryReserve(UUID.randomUUID(), UserPermission.DONOR.getValue()));
            }
            assertFalse(service.tryReserve(UUID.randomUUID(), UserPermission.DONOR.getValue()));
            assertTrue(service.tryReserve(UUID.randomUUID(), UserPermission.ADMIN.getValue()));
            assertFalse(service.tryReserve(UUID.randomUUID(), UserPermission.ADMIN.getValue()));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_2-ユースケース.md
     * 章・見出し: # 03_2-ユースケース > ## 12. 権限別接続人数制限
     * 検証契約: 参加または退出で接続前予約を解放すると、同じ追加枠を後続の接続が再利用できる。
     */
    @Test
    void releasesReservationForLaterConnection() {
        ConfigProperties properties = mockProperties(30, 1, 0);
        UUID firstDonor = UUID.randomUUID();
        UUID secondDonor = UUID.randomUUID();

        try (MockedStatic<ConfigProperties> config = mockStatic(ConfigProperties.class)) {
            config.when(ConfigProperties::getInstance).thenReturn(properties);
            PlayerCapacityService service = new PlayerCapacityService(30);

            assertTrue(service.tryReserve(firstDonor, UserPermission.DONOR.getValue()));
            assertFalse(service.tryReserve(secondDonor, UserPermission.DONOR.getValue()));
            service.release(firstDonor);
            assertTrue(service.tryReserve(secondDonor, UserPermission.DONOR.getValue()));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_4-統合フロー.md
     * 章・見出し: # 03_4-統合フロー > ## 1. ログイン反映 > ### 接続前の人数制限
     * 検証契約: 参加・退出の人数更新と接続前予約の解放を同一処理で行い、参加中の人数を正確に反映する。
     */
    @Test
    void recordsJoinAndQuitWithReservationRelease() {
        ConfigProperties properties = mockProperties(30, 1, 0);
        UUID joinedPlayer = UUID.randomUUID();
        UUID nextDonor = UUID.randomUUID();

        try (MockedStatic<ConfigProperties> config = mockStatic(ConfigProperties.class)) {
            config.when(ConfigProperties::getInstance).thenReturn(properties);
            PlayerCapacityService service = new PlayerCapacityService(30);

            assertTrue(service.tryReserve(joinedPlayer, UserPermission.DONOR.getValue()));
            service.recordPlayerJoin(joinedPlayer);
            assertFalse(service.tryReserve(nextDonor, UserPermission.DONOR.getValue()));

            service.recordPlayerQuit(joinedPlayer);
            assertTrue(service.tryReserve(nextDonor, UserPermission.DONOR.getValue()));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_4-統合フロー.md
     * 章・見出し: # 03_4-統合フロー > ## 1. ログイン反映
     * 検証契約: 起動時に Bukkit の実行時最大人数を設定合計へ変更し、停止時に起動前の値へ復元する。
     */
    @Test
    void appliesAndRestoresRuntimeMaximum() {
        ConfigProperties properties = mockProperties(30, 5, 1);
        Server server = mock(Server.class);
        when(server.getMaxPlayers()).thenReturn(20);

        try (MockedStatic<ConfigProperties> config = mockStatic(ConfigProperties.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            config.when(ConfigProperties::getInstance).thenReturn(properties);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            PlayerCapacityService service = new PlayerCapacityService(0);

            service.applyConfiguredMaximum(server);
            verify(server).setMaxPlayers(36);

            service.restoreConfiguredMaximum(server);
            verify(server).setMaxPlayers(20);
        }
    }

    private ConfigProperties mockProperties(int maxPlayers, int donorExtraPlayers, int adminExtraPlayers) {
        ConfigProperties properties = mock(ConfigProperties.class);
        when(properties.getPlayerCapacityMaxPlayers()).thenReturn(maxPlayers);
        when(properties.getPlayerCapacityDonorExtraPlayers()).thenReturn(donorExtraPlayers);
        when(properties.getPlayerCapacityAdminExtraPlayers()).thenReturn(adminExtraPlayers);
        return properties;
    }
}
