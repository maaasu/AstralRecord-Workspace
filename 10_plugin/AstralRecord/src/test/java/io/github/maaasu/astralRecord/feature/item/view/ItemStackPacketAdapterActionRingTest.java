package io.github.maaasu.astralRecord.feature.item.view;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPermissionService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemStackPacketAdapterActionRingTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### パケットアダプタ登録
     * 検証契約: 設定済みactionが0件から2件へ変わると長押し適格性を更新してinventoryを再同期する。
     */
    @Test
    void actionRingHoldEligibilityChangeRefreshesInventory() throws ReflectiveOperationException {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AtomicBoolean multipleActions = new AtomicBoolean();
        when(plugin.getServer()).thenReturn(server);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(player.getUniqueId()).thenReturn(playerId);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(presetService.selectedPresetIndex(accountId)).thenReturn(0);
        when(presetService.getPresets(accountId)).thenAnswer(ignored -> List.of(preset(
            accountId,
            multipleActions.get() ? List.of("skill_a", "skill_b") : List.of()
        )));
        SkillActionRingService actionRingService = new SkillActionRingService(
            mock(AstralRecord.class), presetService, mock(SkillService.class),
            mock(SkillOwnershipService.class), mock(SkillPermissionService.class)
        );
        AstPlayerCache.put(astPlayer);
        try {
            ItemStackPacketAdapter adapter = new ItemStackPacketAdapter(
                plugin, playerSettingService, actionRingService
            );
            Method refresh = ItemStackPacketAdapter.class.getDeclaredMethod("refreshDisplaySnapshots");
            refresh.setAccessible(true);

            refresh.invoke(adapter);
            clearInvocations(player);
            multipleActions.set(true);
            refresh.invoke(adapter);

            verify(player).updateInventory();

            clearInvocations(player);
            multipleActions.set(false);
            refresh.invoke(adapter);

            verify(player).updateInventory();
        } finally {
            AstPlayerCache.remove(playerId);
        }
    }

    private SkillBindPreset preset(UUID accountId, List<String> activeSlots) {
        return new SkillBindPreset(
            null, accountId, 0, activeSlots, null, List.of(), true, true, 1
        );
    }
}
