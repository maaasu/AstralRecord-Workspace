package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.bukkit.entity.Player;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PassiveSkillSessionLifecycleTest {
    private final AtomicReference<AstPlayer> selected = new AtomicReference<>();
    private MockedStatic<AstPlayerCache> cache;
    private Player bukkit;

    @BeforeEach
    void openPlayerCache() {
        bukkit = mock(Player.class);
        cache = mockStatic(AstPlayerCache.class);
        cache.when(() -> AstPlayerCache.get(bukkit)).thenAnswer(invocation -> selected.get());
    }

    @AfterEach
    void closePlayerCache() {
        cache.close();
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6. passive skill 同期
     * 検証契約: 通常終了後のステータス再計算で旧セッションを再有効化せず、同一アカウントの新セッションと旧参照を分離する。
     */
    @Test
    void endedSessionCannotReactivateThroughStatusRefresh() {
        assertEndedSessionIsolation(false);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6. passive skill 同期
     * 検証契約: 解除callbackが例外になっても旧セッションの終了状態を維持し、再同期・重複終了が新セッションを変更しない。
     */
    @Test
    void failedDeactivationStillClosesTheOldSession() {
        assertEndedSessionIsolation(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6. passive skill 同期
     * 検証契約: 同一アカウントの新セッションを先に公開した場合も旧実行状態を引き継がず、旧セッションの遅れた終了が新状態を削除しない。
     */
    @Test
    void replacingTheSessionBeforeItsQuitNotificationKeepsTheNewState() {
        assertEndedSessionIsolation(false, true, false);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6. passive skill 同期
     * 検証契約: 実行状態を持たない旧セッションから最初の終了通知が遅れて来ても、同一アカウントの新セッションを削除しない。
     */
    @Test
    void lateFirstQuitFromSessionWithoutStateDoesNotRemoveCurrentState() {
        assertEndedSessionIsolation(false, true, true);
    }

    private void assertEndedSessionIsolation(boolean deactivationFails) {
        assertEndedSessionIsolation(deactivationFails, false, false);
    }

    private void assertEndedSessionIsolation(boolean deactivationFails, boolean replaceBeforeQuit, boolean oldNeverActivated) {
        UUID accountId = UUID.randomUUID();
        AstPlayer oldPlayer = player(accountId);
        AstPlayer newPlayer = player(accountId);
        selected.set(oldPlayer);
        AtomicBoolean closed = new AtomicBoolean();
        when(oldPlayer.isPassiveSkillSessionClosed()).thenAnswer(invocation -> closed.get());
        doAnswer(invocation -> { closed.set(true); return null; }).when(oldPlayer).closePassiveSkillSession();

        String skillId = "fixture_passive";
        LearnedSkillInstance learned = new LearnedSkillInstance(UUID.randomUUID(), accountId, skillId, 1,
                List.of(), 0, null, null);
        SkillDefinition definition = mock(SkillDefinition.class);
        when(definition.getId()).thenReturn(skillId);
        when(definition.getImplementationId()).thenReturn(skillId);
        when(definition.getKind()).thenReturn(SkillKind.PASSIVE);
        SkillExecutor executor = mock(SkillExecutor.class);
        when(executor.passiveStatusModifiers(any())).thenReturn(List.of());
        when(executor.passiveResourceRegenMultiplier(any(), any())).thenReturn(1.25D);
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getDefinition(skillId)).thenReturn(definition);
        when(registry.getExecutor(skillId)).thenReturn(executor);
        SkillService skills = mock(SkillService.class);
        when(skills.registry()).thenReturn(registry);
        SkillOwnershipService ownership = mock(SkillOwnershipService.class);
        when(ownership.learnedSkills(oldPlayer)).thenReturn(List.of(learned));
        when(ownership.learnedSkills(newPlayer)).thenReturn(List.of(learned));
        SkillPermissionService permission = mock(SkillPermissionService.class);
        when(permission.isPermitted(oldPlayer, skillId)).thenReturn(true);
        when(permission.isPermitted(newPlayer, skillId)).thenReturn(true);
        SkillBindPresetService presets = mock(SkillBindPresetService.class);
        when(presets.getPresets(accountId)).thenReturn(List.of());
        LearnedSkillResolver resolver = mock(LearnedSkillResolver.class);
        when(resolver.resolve(definition, learned)).thenReturn(new ResolvedLearnedSkill(
                learned, definition, Map.of(StatusType.MAX_HEALTH, 5.0D), Set.of()));
        PassiveSkillService service = new PassiveSkillService(mock(AstralRecord.class), skills, presets,
                ownership, permission, resolver);

        if (!oldNeverActivated) {
            service.reconcileNow(oldPlayer);
            assertTrue(service.isPassiveSkillActive(oldPlayer, skillId));
        }
        doAnswer(invocation -> {
            // 解除callback内の再入と、解除後の状態異常リセットによる再計算を双方検査する。
            assertEquals(0, service.getStatusBonus(oldPlayer, StatusType.MAX_HEALTH, 100));
            assertFalse(service.isPassiveSkillActive(oldPlayer, skillId));
            if (deactivationFails) throw new IllegalStateException("fixture deactivation failure");
            return null;
        }).when(executor).onDeactivate(any(PassiveSkillContext.class));

        if (replaceBeforeQuit) {
            selected.set(newPlayer);
            service.reconcileNow(newPlayer);
            service.onPlayerQuit(oldPlayer);
            assertFalse(service.isPassiveSkillActive(oldPlayer, skillId));
            assertTrue(service.isPassiveSkillActive(newPlayer, skillId));
            assertEquals(5, service.getStatusBonus(newPlayer, StatusType.MAX_HEALTH, 100));
            verify(executor, times(oldNeverActivated ? 1 : 2)).onActivate(any(PassiveSkillContext.class));
            verify(executor, times(oldNeverActivated ? 0 : 1)).onDeactivate(any(PassiveSkillContext.class));
            return;
        }

        if (deactivationFails) assertThrows(IllegalStateException.class, () -> service.onPlayerQuit(oldPlayer));
        else service.onPlayerQuit(oldPlayer);
        assertEquals(0, service.getStatusBonus(oldPlayer, StatusType.MAX_HEALTH, 100));
        assertEquals(1, service.getResourceRegenMultiplier(oldPlayer, SkillResourceType.MANA));
        service.markDirty(oldPlayer);
        service.reconcileNow(oldPlayer);
        assertFalse(service.isPassiveSkillActive(oldPlayer, skillId));
        verify(executor, times(1)).onActivate(any(PassiveSkillContext.class));

        selected.set(newPlayer);
        service.reconcileNow(newPlayer);
        assertTrue(service.isPassiveSkillActive(newPlayer, skillId));
        assertEquals(5, service.getStatusBonus(newPlayer, StatusType.MAX_HEALTH, 100));
        assertEquals(1.25, service.getResourceRegenMultiplier(newPlayer, SkillResourceType.MANA));
        service.onPlayerQuit(oldPlayer);
        service.reconcileNow(oldPlayer);
        assertFalse(service.isPassiveSkillActive(oldPlayer, skillId));
        assertEquals(0, service.getStatusBonus(oldPlayer, StatusType.MAX_HEALTH, 100));
        assertTrue(service.isPassiveSkillActive(newPlayer, skillId));
        verify(executor, times(2)).onActivate(any(PassiveSkillContext.class));
        verify(executor, times(1)).onDeactivate(any(PassiveSkillContext.class));
        verify(oldPlayer, times(1)).closePassiveSkillSession();
    }

    private AstPlayer player(UUID accountId) {
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(bukkit);
        return player;
    }
}
