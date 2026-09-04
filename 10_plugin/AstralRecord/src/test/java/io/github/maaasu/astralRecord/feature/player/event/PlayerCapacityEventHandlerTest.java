package io.github.maaasu.astralRecord.feature.player.event;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerCapacityService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerCapacityEventHandlerTest {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### 権限別接続人数制限
     * 検証契約: 寄付者の追加枠が満杯の場合、接続前イベントを KICK_FULL と参加可能人数到達メッセージで拒否する。
     */
    @Test
    void rejectsConnectionWhenPermissionSpecificCapacityIsFull() {
        UUID playerUuid = UUID.randomUUID();
        UserService userService = mock(UserService.class);
        PlayerCapacityService capacityService = mock(PlayerCapacityService.class);
        UserModel user = mock(UserModel.class);
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        when(event.getUniqueId()).thenReturn(playerUuid);
        when(userService.getUser(playerUuid)).thenReturn(user);
        when(user.getPermission()).thenReturn(UserPermission.DONOR.getValue());
        when(capacityService.tryReserve(playerUuid, UserPermission.DONOR.getValue())).thenReturn(false);

        new PlayerCapacityEventHandler(userService, capacityService).onAsyncPreLogin(event);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_FULL), messageCaptor.capture());
        assertEquals(
                PLAIN_TEXT.serialize(PlayerMsgResource.getComponent(PlayerMsgId.P_7140.getId())),
                PLAIN_TEXT.serialize(messageCaptor.getValue())
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### 権限別接続人数制限
     * 検証契約: ログイン段階で拒否された接続の予約を解放し、後続の接続が枠を再利用できる。
     */
    @SuppressWarnings("deprecation")
    @Test
    void releasesReservationOnLoginDenial() {
        UUID playerUuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        PlayerLoginEvent event = mock(PlayerLoginEvent.class);
        when(event.getResult()).thenReturn(PlayerLoginEvent.Result.KICK_FULL);
        when(event.getPlayer()).thenReturn(player);
        UserService userService = mock(UserService.class);
        PlayerCapacityService capacityService = mock(PlayerCapacityService.class);

        new PlayerCapacityEventHandler(userService, capacityService).onPlayerLogin(event);

        verify(capacityService).release(playerUuid);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### 権限別接続人数制限
     * 検証契約: 既に他の接続制御で拒否されたイベントには、ユーザー取得・人数枠予約を追加しない。
     */
    @Test
    void leavesAlreadyRejectedConnectionUndisturbed() {
        UserService userService = mock(UserService.class);
        PlayerCapacityService capacityService = mock(PlayerCapacityService.class);
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);

        new PlayerCapacityEventHandler(userService, capacityService).onAsyncPreLogin(event);

        verifyNoInteractions(userService, capacityService);
        verify(event, never()).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_FULL), org.mockito.ArgumentMatchers.any(Component.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### 権限別接続人数制限
     * 検証契約: 参加・退出時に接続前予約を解放し、次の接続が枠を利用できる状態に戻す。
     */
    @Test
    void releasesReservationOnJoinAndQuit() {
        UUID playerUuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
        when(joinEvent.getPlayer()).thenReturn(player);
        when(quitEvent.getPlayer()).thenReturn(player);
        UserService userService = mock(UserService.class);
        PlayerCapacityService capacityService = mock(PlayerCapacityService.class);
        PlayerCapacityEventHandler handler = new PlayerCapacityEventHandler(userService, capacityService);

        handler.onPlayerJoin(joinEvent);
        handler.onPlayerQuit(quitEvent);

        verify(capacityService).recordPlayerJoin(playerUuid);
        verify(capacityService).recordPlayerQuit(playerUuid);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### 権限別接続人数制限
     * 検証契約: ワールド参加前の接続切断でも接続前予約を解放する。
     */
    @Test
    void releasesReservationOnConnectionClose() {
        UUID playerUuid = UUID.randomUUID();
        PlayerConnectionCloseEvent event = mock(PlayerConnectionCloseEvent.class);
        when(event.getPlayerUniqueId()).thenReturn(playerUuid);
        UserService userService = mock(UserService.class);
        PlayerCapacityService capacityService = mock(PlayerCapacityService.class);

        new PlayerCapacityEventHandler(userService, capacityService).onPlayerConnectionClose(event);

        verify(capacityService).release(playerUuid);
    }
}
