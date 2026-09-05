package io.github.maaasu.astralRecord.feature.item.service;

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
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 装備中防具・アクセサリの破損通知
     * 検証契約: 周期実行時は破損した防具・アクセサリがあるプレイヤーへ装備名をまとめて1通通知する。
     */
    @Test
    void sendsOneSummaryMessageForEachPlayerWithDamagedEquipment() {
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer player = mock(AstPlayer.class);
        when(durabilityService.getDamagedArmorAndAccessoryDisplayNames(player))
            .thenReturn(List.of("&c壊れた兜", "&e傷ついた指輪"));
        EquipmentDurabilityReminderTask task = new EquipmentDurabilityReminderTask(
            durabilityService,
            messageService
        );

        try (MockedStatic<AstPlayerCache> players = mockStatic(AstPlayerCache.class)) {
            players.when(AstPlayerCache::getAll).thenReturn(List.of(player));

            task.notifyDamagedEquipment();
        }

        verify(messageService).send(
            player,
            PlayerMsgId.P_5283,
            "&c壊れた兜、&e傷ついた指輪"
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 装備中防具・アクセサリの破損通知
     * 検証契約: 破損した装備がない周期はプレイヤー向けメッセージを送信しない。
     */
    @Test
    void doesNotSendWhenNoDamagedEquipmentExists() {
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer player = mock(AstPlayer.class);
        when(durabilityService.getDamagedArmorAndAccessoryDisplayNames(player)).thenReturn(List.of());
        EquipmentDurabilityReminderTask task = new EquipmentDurabilityReminderTask(
            durabilityService,
            messageService
        );

        try (MockedStatic<AstPlayerCache> players = mockStatic(AstPlayerCache.class)) {
            players.when(AstPlayerCache::getAll).thenReturn(List.of(player));

            task.notifyDamagedEquipment();
        }

        verifyNoInteractions(messageService);
    }
}
