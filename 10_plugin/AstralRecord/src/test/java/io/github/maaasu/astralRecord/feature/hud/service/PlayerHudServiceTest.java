package io.github.maaasu.astralRecord.feature.hud.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonSidebarInfo;
import io.github.maaasu.astralRecord.feature.hud.view.PlayerHudView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerHudServiceTest extends MockBukkitTestBase {

    @AfterEach
    void clearAstPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-サービス.md
     * 章・見出し: # 10_3-サービス > ## 8. 全 player 更新（内部）
     * 検証契約: skilltree ワールドでは SkillTreeService の CP / PP 残高をサイドバーへ渡し、
     * それ以外のワールドではスキルツリー行を渡さない。
     */
    @Test
    void passesSkillTreePointsToSidebarOnlyInSkillTreeWorld() throws Exception {
        PlayerMock player = server().addPlayer("HudPlayer");
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);

        StatusService statusService = mock(StatusService.class);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        AccountService accountService = mock(AccountService.class);
        CurrencyService currencyService = mock(CurrencyService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        ConditionService conditionService = mock(ConditionService.class);
        BossChallengeService bossChallengeService = mock(BossChallengeService.class);
        WorldService worldService = mock(WorldService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);

        when(statusService.getStatus(astPlayer)).thenReturn(StatusSnapshot.empty());
        List<ActiveBuff> activeBuffs = List.of();
        when(statusService.getActiveBuffs(astPlayer)).thenReturn(activeBuffs);
        when(playerClassService.getDisplayName(astPlayer.getClassId())).thenReturn("剣士");
        when(worldService.resolveDisplayName(player.getWorld())).thenReturn("スキルツリー");
        when(skillTreeService.isSkillTreeWorld(player.getWorld())).thenReturn(true, false);
        when(skillTreeService.currentClassPointLabel(astPlayer)).thenReturn("CP[剣士]");
        when(skillTreeService.availableClassPoints(astPlayer)).thenReturn(7);
        when(skillTreeService.availablePassivePoints(astPlayer)).thenReturn(8);

        try (MockedConstruction<PlayerHudView> views = mockConstruction(PlayerHudView.class);
             MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            Server bukkitServer = mock(Server.class);
            when(bukkitServer.getAverageTickTime()).thenReturn(20.0D);
            bukkit.when(Bukkit::getServer).thenReturn(bukkitServer);

            PlayerHudService service = new PlayerHudService(
                    statusService,
                    playerClassService,
                    accountService,
                    currencyService,
                    playerSettingService,
                    conditionService,
                    bossChallengeService,
                    worldService,
                    skillTreeService
            );
            service.setPrimaryActionBarRenderer(player.getUniqueId(), ignored -> Component.empty());
            invokeUpdateAll(service);
            PlayerHudView view = views.constructed().get(0);
            String regionName = player.getWorld().getName();
            verify(view).renderSidebar(
                    eq(player), eq(20.0D), eq(1), eq(0.0D), eq(1), eq("剣士"), eq(0L),
                    eq("CP[剣士]"), eq(7), eq(8), eq("スキルツリー"), eq(regionName), eq(0),
                    eq(false), isNull(BossChallengeSidebarInfo.class), isNull(DungeonSidebarInfo.class),
                    eq(false), eq(activeBuffs)
            );

            invokeUpdateAll(service);
            verify(view).renderSidebar(
                    eq(player), eq(20.0D), eq(1), eq(0.0D), eq(1), eq("剣士"), eq(0L),
                    isNull(String.class), eq(0), eq(0), eq("スキルツリー"), eq(regionName), eq(0),
                    eq(false), isNull(BossChallengeSidebarInfo.class), isNull(DungeonSidebarInfo.class),
                    eq(false), eq(activeBuffs)
            );
        }
    }

    private void invokeUpdateAll(PlayerHudService service) throws Exception {
        Method updateAll = PlayerHudService.class.getDeclaredMethod("updateAll");
        updateAll.setAccessible(true);
        updateAll.invoke(service);
    }
}
