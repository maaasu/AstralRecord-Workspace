package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EquipmentDurabilityReminderTaskTest {

    /**
     * 周期実行時は破損した防具・アクセサリがあるプレイヤーへ装備名をまとめて1通通知する。
     */
    @Test
    void sendsOneSummaryMessageForEachPlayerWithBrokenEquipment() {
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        when(player.getAccount()).thenReturn(account);
        when(durabilityService.getBrokenArmorAndAccessoryDisplayNames(player))
            .thenReturn(List.of("&c壊れた兜", "&e傷ついた指輪"));
        EquipmentDurabilityReminderTask task = new EquipmentDurabilityReminderTask(
            durabilityService,
            messageService
        );

        try (MockedStatic<AstPlayerCache> players = mockStatic(AstPlayerCache.class)) {
            players.when(AstPlayerCache::getAll).thenReturn(List.of(player));

            task.notifyBrokenEquipment();
        }

        verify(messageService).send(
            player,
            PlayerMsgId.P_5283,
            "&c壊れた兜、&e傷ついた指輪"
        );
    }

    /**
     * 破損した装備がない周期はプレイヤー向けメッセージを送信しない。
     */
    @Test
    void doesNotSendWhenNoBrokenEquipmentExists() {
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        when(player.getAccount()).thenReturn(account);
        when(durabilityService.getBrokenArmorAndAccessoryDisplayNames(player)).thenReturn(List.of());
        EquipmentDurabilityReminderTask task = new EquipmentDurabilityReminderTask(
            durabilityService,
            messageService
        );

        try (MockedStatic<AstPlayerCache> players = mockStatic(AstPlayerCache.class)) {
            players.when(AstPlayerCache::getAll).thenReturn(List.of(player));

            task.notifyBrokenEquipment();
        }

        verifyNoInteractions(messageService);
    }

    /**
     * ADMINでは保持中のプレイヤー用装備を走査せず、破損通知も送信しない。
     */
    @Test
    void adminModeDoesNotInspectRetainedPlayerEquipment() {
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(account.getMode()).thenReturn(AccountMode.ADMIN);
        when(player.getAccount()).thenReturn(account);
        EquipmentDurabilityReminderTask task = new EquipmentDurabilityReminderTask(durabilityService, messageService);

        try (MockedStatic<AstPlayerCache> players = mockStatic(AstPlayerCache.class)) {
            players.when(AstPlayerCache::getAll).thenReturn(List.of(player));
            task.notifyBrokenEquipment();
        }

        verifyNoInteractions(durabilityService, messageService);
    }
}
