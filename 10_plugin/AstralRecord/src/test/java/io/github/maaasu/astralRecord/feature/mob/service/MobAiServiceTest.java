package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.CombatStyle;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCombatConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobAiServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### アカウントモード変更・オンライン反映
     * 検証契約: ADMIN モードのオンラインプレイヤーは Mob AI のターゲット候補に含めない。
     */
    @Test
    void ignoresAdministratorModePlayerAsMobTarget() throws ReflectiveOperationException {
        World world = mock(World.class);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                enemyTemplate(),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );
        Player admin = activePlayer(world, 1.0D);
        UUID adminId = UUID.randomUUID();
        when(admin.getUniqueId()).thenReturn(adminId);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.ADMIN);

        MobAiService aiService = new MobAiService(
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(admin));
            cache.when(() -> AstPlayerCache.get(admin)).thenReturn(astPlayer);

            invokeTickAggro(aiService, instance);
        }

        assertNull(instance.targetId());
        assertEquals(MobState.IDLE, instance.state());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### 一時挑発と追跡対象解決
     * 検証契約: 有効な挑発者はdeaggroRange外でも固定し、期限切れ後は通常のHIGHEST_THREAT対象へ戻る。
     */
    @Test
    void tauntOverridesDeaggroUntilExpiryThenReturnsToHighestThreat() throws ReflectiveOperationException {
        World world = mock(World.class);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                enemyTemplate(),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );
        instance.state(MobState.AGGRO);
        Player taunter = activePlayer(world, 50.0D);
        Player threatTarget = activePlayer(world, 3.0D);
        UUID taunterId = UUID.randomUUID();
        UUID threatTargetId = UUID.randomUUID();
        when(taunter.getUniqueId()).thenReturn(taunterId);
        when(threatTarget.getUniqueId()).thenReturn(threatTargetId);
        instance.threatTable().add(threatTargetId, 100.0D);

        MobService mobService = mock(MobService.class);
        MobTauntService tauntService = mock(MobTauntService.class);
        when(tauntService.activeTaunter(instance)).thenReturn(taunterId, taunterId, null, null);
        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(MobSkillService.class),
                null,
                null,
                null,
                tauntService
        );

        AstPlayer taunterPlayer = gameplayAstPlayer();
        AstPlayer threatTargetPlayer = gameplayAstPlayer();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(taunter)).thenReturn(taunterPlayer);
            cache.when(() -> AstPlayerCache.get(threatTarget)).thenReturn(threatTargetPlayer);
            bukkit.when(() -> Bukkit.getPlayer(taunterId)).thenReturn(taunter);
            bukkit.when(() -> Bukkit.getPlayer(threatTargetId)).thenReturn(threatTarget);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(taunter, threatTarget));

            invokeTickAggro(aiService, instance);
            assertEquals(taunterId, instance.targetId());
            assertEquals(MobState.AGGRO, instance.state());

            invokeTickAggro(aiService, instance);
            assertEquals(threatTargetId, instance.targetId());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: 表示中の疑似 Player NPC の transform は viewer 探索間隔に依存せず毎 tick 同期する。
     */
    @Test
    void playerSkinPacketTransformsAreSynchronizedEveryTick() {
        MobService mobService = mock(MobService.class);
        when(mobService.getInstances()).thenReturn(List.of());
        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );

        aiService.tick();

        verify(mobService).syncPlayerSkinPacketViews();
        verify(mobService, never()).updateViewers();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: viewer 更新tickでは、viewer集合を更新してからcached viewerを使ったenemy破棄判定を実行する。
     */
    @Test
    void viewerCleanupUsesCachedViewersImmediatelyAfterViewerRefresh() throws ReflectiveOperationException {
        MobService mobService = mock(MobService.class);
        when(mobService.getInstances()).thenReturn(List.of());
        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );
        setInternalTick(aiService, 4L);

        aiService.tick();

        InOrder order = inOrder(mobService);
        order.verify(mobService).updateViewers();
        order.verify(mobService).destroyEnemiesOutsideViewDistanceUsingCachedViewers();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: NPCのWANDERが30秒周期で配置アンカーへの経路を設定し、テレポートを実行しない。
     */
    @Test
    void wanderingNpcPeriodicallyRoutesToPlacementAnchorWithoutTeleporting() throws ReflectiveOperationException {
        MobService mobService = mock(MobService.class);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                wanderingNpcTemplate(),
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        instance.currentLocation(new Location(null, 20.0D, 64.0D, 20.0D));
        when(mobService.getInstances()).thenReturn(List.of(instance));
        when(mobService.syncLocation(instance)).thenReturn(true);

        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );
        long routeTick = Math.floorMod(-(long) instance.instanceId().hashCode(), 20L * 30L);
        if (routeTick == 0L) {
            routeTick = 20L * 30L;
        }
        setInternalTick(aiService, routeTick - 1L);

        aiService.tick();

        verify(mobService, never()).resetPosition(any(MobInstance.class), any(Location.class));
        verify(mobService).moveToward(
                same(instance),
                argThat(anchor -> anchor.getX() == 0.0D
                        && anchor.getY() == 64.0D
                        && anchor.getZ() == 0.0D),
                eq(0.75D),
                eq(routeTick)
        );
        assertEquals(0.0D, instance.wanderTarget().getX());
        assertEquals(0.0D, instance.wanderTarget().getZ());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: WANDER目的地への水平移動が10秒間ないNPCを緊急テレポートで配置アンカーへ戻す。
     */
    @Test
    void stalledWanderingNpcIsResetAfterNoHorizontalProgress() throws ReflectiveOperationException {
        MobService mobService = mock(MobService.class);
        MobInstance instance = new MobInstance(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                wanderingNpcTemplate(),
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        instance.wanderTarget(new Location(null, 8.0D, 64.0D, 0.0D));
        when(mobService.getInstances()).thenReturn(List.of(instance));
        when(mobService.syncLocation(instance)).thenReturn(true);

        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );
        long firstDecisionTick = Math.floorMod(-(long) instance.instanceId().hashCode(), 10L);
        if (firstDecisionTick == 0L) {
            firstDecisionTick = 10L;
        }

        for (long decisionTick = firstDecisionTick;
             decisionTick <= firstDecisionTick + 20L * 10L;
             decisionTick += 10L) {
            setInternalTick(aiService, decisionTick - 1L);
            aiService.tick();
        }

        verify(mobService, never()).resetPosition(any(MobInstance.class), any(Location.class));

        setInternalTick(aiService, firstDecisionTick + 20L * 10L + 9L);
        aiService.tick();

        verify(mobService).resetPosition(
                same(instance),
                argThat(anchor -> anchor.getX() == 0.0D
                        && anchor.getY() == 64.0D
                        && anchor.getZ() == 0.0D)
        );
        assertNull(instance.wanderTarget());
        assertEquals(firstDecisionTick + 20L * 10L + 10L, instance.lastWanderTeleportTick());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: 緊急テレポートから3分以内に再び10秒間停止しても、アンカーへの経路設定だけを行う。
     */
    @Test
    void stalledWanderingNpcUsesAnchorRouteDuringTeleportCooldown() throws ReflectiveOperationException {
        MobService mobService = mock(MobService.class);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                wanderingNpcTemplate(),
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        instance.currentLocation(new Location(null, 20.0D, 64.0D, 20.0D));
        instance.wanderTarget(new Location(null, 8.0D, 64.0D, 0.0D));
        instance.navBlockedSinceTick(0L);
        instance.navLastObservedLocation(instance.currentLocation());
        instance.lastWanderTeleportTick(0L);
        when(mobService.getInstances()).thenReturn(List.of(instance));
        when(mobService.syncLocation(instance)).thenReturn(true);

        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );
        long decisionTick = 20L * 10L;
        while (Math.floorMod(instance.instanceId().hashCode() + decisionTick, 10L) != 0L) {
            decisionTick++;
        }
        setInternalTick(aiService, decisionTick - 1L);

        aiService.tick();

        verify(mobService, never()).resetPosition(any(MobInstance.class), any(Location.class));
        verify(mobService).moveToward(
                same(instance),
                argThat(anchor -> anchor.getX() == 0.0D
                        && anchor.getY() == 64.0D
                        && anchor.getZ() == 0.0D),
                eq(0.75D),
                eq(decisionTick)
        );
        assertEquals(0.0D, instance.wanderTarget().getX());
        assertEquals(0.0D, instance.wanderTarget().getZ());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 9. Mob ターゲット設定
     * 検証契約: スポーン地点から上下に8ブロックを超えたMobは、水平距離がleashRange以内でもLEASHEDになる。
     */
    @Test
    void verticalSpawnDistanceTriggersLeash() throws ReflectiveOperationException {
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                enemyTemplate(),
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        instance.currentLocation(new Location(null, 0.0D, 73.0D, 0.0D));

        MobAiService aiService = new MobAiService(
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );

        assertTrue(invokeIsLeashed(aiService, instance));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 9. Mob ターゲット設定
     * 検証契約: 上下差8ブロックは許容し、9ブロックからLEASHEDとする。水平距離による従来のleash判定も維持する。
     */
    @Test
    void verticalLeashBoundaryAndHorizontalLeashRemainStable() throws ReflectiveOperationException {
        World world = mock(World.class);
        MobAiService aiService = new MobAiService(
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );
        MobInstance boundary = new MobInstance(
                UUID.randomUUID(),
                enemyTemplate(),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );
        boundary.currentLocation(new Location(world, 0.0D, 72.0D, 0.0D));
        MobInstance horizontal = new MobInstance(
                UUID.randomUUID(),
                enemyTemplate(),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );
        horizontal.currentLocation(new Location(world, 101.0D, 64.0D, 0.0D));

        assertFalse(invokeIsLeashed(aiService, boundary));
        assertTrue(invokeIsLeashed(aiService, horizontal));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: 上下に大きく外れたLEASHEDのMobは、経路探索ではなくスポーン地点へ直接リセットする。
     */
    @Test
    void verticallyLeashedMobIsResetToSpawn() throws ReflectiveOperationException {
        MobService mobService = mock(MobService.class);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                enemyTemplate(),
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        instance.currentLocation(new Location(null, 0.0D, 73.0D, 0.0D));
        instance.state(MobState.LEASHED);
        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(MobSkillService.class)
        );

        invokeTickLeashed(aiService, instance);

        verify(mobService).resetPosition(
                same(instance),
                argThat(spawn -> spawn.getX() == 0.0D
                        && spawn.getY() == 64.0D
                        && spawn.getZ() == 0.0D)
        );
        verify(mobService, never()).moveToward(any(MobInstance.class), any(Location.class), anyDouble(), anyLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: 上下照準可能な次スキルが三次元射程内で高低差だけが1.25 blockを超える場合、COMBATのAIは追跡せず現在地でスキル発動を試みる。
     */
    @Test
    void verticalTargetingSkillCastsFromCurrentPositionInsteadOfChasing() throws ReflectiveOperationException {
        World world = mock(World.class);
        UUID targetId = UUID.randomUUID();
        MobSkillBinding binding = new MobSkillBinding("mob_vertical_test", 16.0D, null, null, Map.of());
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                verticalTargetingEnemyTemplate(binding),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );
        instance.state(MobState.COMBAT);
        instance.targetId(targetId);
        Player target = activePlayer(world, 10.0D, 68.0D);
        when(target.getUniqueId()).thenReturn(targetId);

        MobService mobService = mock(MobService.class);
        MobSkillService mobSkillService = mock(MobSkillService.class);
        when(mobSkillService.isWithinActivationRange(instance, binding, target, 12.25D)).thenReturn(true);
        when(mobSkillService.tryCast(instance, binding, target, 0L)).thenReturn(true);
        MobAiService aiService = new MobAiService(mobService, mock(MobCombatService.class), mobSkillService);

        AstPlayer gameplayTarget = gameplayAstPlayer();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(gameplayTarget);
            bukkit.when(() -> Bukkit.getPlayer(targetId)).thenReturn(target);

            invokeTickCombatHold(aiService, instance);
        }

        assertEquals(MobState.COMBAT, instance.state());
        verify(mobService).stopPathfinding(instance);
        verify(mobSkillService).tryCast(instance, binding, target, 0L);
        verify(mobService, never()).moveToward(any(MobInstance.class), any(Location.class), anyDouble(), anyLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: 上下照準可能な次スキルが三次元射程内で高低差だけが1.25 blockを超える場合、AGGROのAIは追跡せずCOMBATへ遷移してPathfinderを停止する。
     */
    @Test
    void verticalTargetingSkillEntersCombatInsteadOfChasing() throws ReflectiveOperationException {
        World world = mock(World.class);
        UUID targetId = UUID.randomUUID();
        MobSkillBinding binding = new MobSkillBinding("mob_vertical_test", 16.0D, null, null, Map.of());
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                verticalTargetingEnemyTemplate(binding),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );
        instance.state(MobState.AGGRO);
        instance.targetId(targetId);
        Player target = activePlayer(world, 10.0D, 68.0D);
        when(target.getUniqueId()).thenReturn(targetId);

        MobService mobService = mock(MobService.class);
        MobSkillService mobSkillService = mock(MobSkillService.class);
        when(mobSkillService.isWithinActivationRange(instance, binding, target, 12.25D)).thenReturn(true);
        MobAiService aiService = new MobAiService(mobService, mock(MobCombatService.class), mobSkillService);

        AstPlayer gameplayTarget = gameplayAstPlayer();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(gameplayTarget);
            bukkit.when(() -> Bukkit.getPlayer(targetId)).thenReturn(target);

            invokeTickAggro(aiService, instance);
        }

        assertEquals(MobState.COMBAT, instance.state());
        verify(mobService).stopPathfinding(instance);
        verify(mobService, never()).moveToward(any(MobInstance.class), any(Location.class), anyDouble(), anyLong());
    }

    private static MobTemplate wanderingNpcTemplate() {
        return new MobTemplate(
                1,
                "npc:test_wanderer",
                MobCategory.NPC,
                "Test Wanderer",
                null,
                1,
                EntityType.VILLAGER,
                false,
                null,
                List.of(),
                List.of(),
                null,
                MobVariantConfig.DEFAULT,
                MobEquipmentConfig.EMPTY,
                List.of(new MobBaseStat("MAX_HEALTH", 100.0D)),
                MobShieldConfig.EMPTY,
                new MobIdleConfig(IdleBehavior.WANDER, 8.0D, 0.75D),
                true,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        );
    }

    private static Player activePlayer(World world, double x) {
        return activePlayer(world, x, 64.0D);
    }

    private static Player activePlayer(World world, double x, double y) {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, x, y, 0.0D));
        when(player.getEyeLocation()).thenReturn(new Location(world, x, y + 1.6D, 0.0D));
        return player;
    }

    private static AstPlayer gameplayAstPlayer() {
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        return astPlayer;
    }

    private static MobTemplate verticalTargetingEnemyTemplate(MobSkillBinding binding) {
        return new MobTemplate(
                1,
                "enemy:vertical_targeting_test",
                MobCategory.ENEMY,
                "Vertical Targeting Test",
                null,
                1,
                EntityType.SKELETON,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(new MobBaseStat("MAX_HEALTH", 100.0D)),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                new MobTargetingConfig(TargetStrategy.HIGHEST_THREAT, 100.0D, 100.0D, 100.0D, false),
                new MobCombatConfig(CombatStyle.RANGED, 12.0D, null, List.of(binding)),
                null
        );
    }

    private static MobTemplate enemyTemplate() {
        return new MobTemplate(
                1,
                "enemy:taunt_ai_test",
                MobCategory.ENEMY,
                "Taunt AI Test",
                null,
                1,
                EntityType.ARMOR_STAND,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(new MobBaseStat("MAX_HEALTH", 100.0D)),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                new MobTargetingConfig(TargetStrategy.HIGHEST_THREAT, 100.0D, 10.0D, 100.0D, false),
                null,
                null
        );
    }

    private static void invokeTickAggro(MobAiService aiService, MobInstance instance) throws ReflectiveOperationException {
        Method method = MobAiService.class.getDeclaredMethod("tickAggro", MobInstance.class);
        method.setAccessible(true);
        method.invoke(aiService, instance);
    }

    private static void invokeTickCombatHold(MobAiService aiService, MobInstance instance) throws ReflectiveOperationException {
        Method method = MobAiService.class.getDeclaredMethod("tickCombatHold", MobInstance.class);
        method.setAccessible(true);
        method.invoke(aiService, instance);
    }

    private static void invokeTickLeashed(MobAiService aiService, MobInstance instance) throws ReflectiveOperationException {
        Method method = MobAiService.class.getDeclaredMethod("tickLeashed", MobInstance.class);
        method.setAccessible(true);
        method.invoke(aiService, instance);
    }

    private static boolean invokeIsLeashed(MobAiService aiService, MobInstance instance) throws ReflectiveOperationException {
        Method method = MobAiService.class.getDeclaredMethod("isLeashed", MobInstance.class);
        method.setAccessible(true);
        return (boolean) method.invoke(aiService, instance);
    }

    private static void setInternalTick(MobAiService aiService, long tick) throws ReflectiveOperationException {
        Field field = MobAiService.class.getDeclaredField("internalTick");
        field.setAccessible(true);
        field.setLong(aiService, tick);
    }
}
