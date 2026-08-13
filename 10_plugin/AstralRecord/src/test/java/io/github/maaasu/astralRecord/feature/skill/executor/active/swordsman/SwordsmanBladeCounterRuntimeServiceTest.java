package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.Bukkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SwordsmanBladeCounterRuntimeServiceTest extends MockBukkitTestBase {

    private SwordsmanBladeCounterRuntimeService runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 通常攻撃で先に消費した受付枠が回避hitへ反応せず、軽減・成功演出・反撃を行わない。
     */
    @Test
    void evadedMobDirectHitConsumesAttemptWithoutCounter() {
        RuntimeHarness harness = runtimeHarness();
        AstPlayer player = activePlayer(harness, 3);
        AstEntity mob = AstEntity.mob(DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D));

        var modification = runtime.modifyIncomingDirectDamage(
                mob,
                AstEntity.player(player),
                AttackType.RANGED,
                DamageSource.SKILL,
                DamageResult.evaded(25.0D, 25.0D, 100.0D)
        );
        modification.afterDamageApplied().run();

        assertEquals(1.0D, modification.damageMultiplier(), 0.0001D);
        assertEquals(2, runtime.remainingCounters(player.getBukkit().getUniqueId()));
        verifyNoInteractions(harness.effects);
        verify(harness.damageService, never()).attack(any(), any(), any(), anyList(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 非回避の0damage直接hitでも50%倍率を返し、枠消費済みの受付から元hit反映後処理でのみ反撃する。
     */
    @Test
    void zeroDamageMobDirectHitDefersCounterUntilPostHit() {
        RuntimeHarness harness = runtimeHarness();
        AstPlayer player = activePlayer(harness, 3);
        AstEntity mob = AstEntity.mob(DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D));

        var modification = runtime.modifyIncomingDirectDamage(
                mob,
                AstEntity.player(player),
                AttackType.MAGIC,
                DamageSource.SKILL,
                new DamageResult(0.0D)
        );

        assertEquals(0.5D, modification.damageMultiplier(), 0.0001D);
        assertEquals(2, runtime.remainingCounters(player.getBukkit().getUniqueId()));
        verify(harness.damageService, never()).attack(any(), any(), any(), anyList(), any());

        modification.afterDamageApplied().run();

        verify(harness.damageService).attack(
                any(AstEntity.class),
                eq(mob),
                eq(AttackType.MELEE),
                anyList(),
                eq(DamageSource.SKILL)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: OTHERとPvPは通常攻撃で作った受付枠を消費せず、後処理も作らない。
     */
    @Test
    void otherAndPvpDamageDoNotConsumeCounterAttempt() {
        RuntimeHarness harness = runtimeHarness();
        AstPlayer player = activePlayer(harness, 3);
        AstEntity victim = AstEntity.player(player);
        AstEntity mob = AstEntity.mob(DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D));
        AstPlayer otherPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);

        var other = runtime.modifyIncomingDirectDamage(
                mob,
                victim,
                AttackType.MELEE,
                DamageSource.OTHER,
                new DamageResult(10.0D)
        );
        var pvp = runtime.modifyIncomingDirectDamage(
                AstEntity.player(otherPlayer),
                victim,
                AttackType.MELEE,
                DamageSource.NORMAL_ATTACK,
                new DamageResult(10.0D)
        );

        assertEquals(1.0D, other.damageMultiplier(), 0.0001D);
        assertEquals(1.0D, pvp.damageMultiplier(), 0.0001D);
        assertEquals(2, runtime.remainingCounters(player.getBukkit().getUniqueId()));
        verifyNoInteractions(harness.effects);
        verifyNoInteractions(harness.damageService);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 通常攻撃の試行ごとに成功・失敗を問わず回数を消費し、0回でruntimeと表示を終了する。
     */
    @Test
    void normalAttackAttemptsConsumeCountersAndExhaustRuntime() {
        RuntimeHarness harness = runtimeHarness();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(
                Map.of(StatusType.MAX_HEALTH, 100.0D),
                100.0D,
                0.0D,
                0.0D
        ));
        installRuntimeEntry(player, 3);

        runtime.onNormalAttack(player);
        assertEquals(2, runtime.remainingCounters(player.getBukkit().getUniqueId()));
        runtime.onNormalAttack(player);
        assertEquals(1, runtime.remainingCounters(player.getBukkit().getUniqueId()));
        runtime.onNormalAttack(player);
        assertEquals(0, runtime.remainingCounters(player.getBukkit().getUniqueId()));
        verify(harness.damageService, never()).attack(any(), any(), any(), anyList(), any());
    }

    private RuntimeHarness runtimeHarness() {
        DamageService damageService = mock(DamageService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        runtime = new SwordsmanBladeCounterRuntimeService(
                MockBukkit.createMockPlugin("BladeCounterRuntimeTest"),
                damageService,
                effects
        );
        return new RuntimeHarness(damageService, effects);
    }

    private AstPlayer activePlayer(RuntimeHarness harness, int counters) {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(
                Map.of(StatusType.MAX_HEALTH, 100.0D),
                100.0D,
                0.0D,
                0.0D
        ));
        installRuntimeEntry(player, counters);
        runtime.onNormalAttack(player);
        clearInvocations(harness.effects, harness.damageService);
        return player;
    }

    @SuppressWarnings("unchecked")
    private void installRuntimeEntry(AstPlayer player, int counters) {
        try {
            Class<?> entryType = Class.forName(SwordsmanBladeCounterRuntimeService.class.getName() + "$RuntimeEntry");
            Constructor<?> constructor = entryType.getDeclaredConstructor(
                    AstPlayer.class,
                    AstEntity.class,
                    BladeCounterState.class,
                    List.class,
                    long.class,
                    double.class,
                    double.class
            );
            constructor.setAccessible(true);
            Object entry = constructor.newInstance(
                    player,
                    AstEntity.player(player),
                    new BladeCounterState(counters, Bukkit.getCurrentTick() + 400L),
                    List.of(),
                    10L,
                    1.0D,
                    0.5D
            );
            Field entriesField = SwordsmanBladeCounterRuntimeService.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            Map<UUID, Object> entries = (Map<UUID, Object>) entriesField.get(runtime);
            entries.put(player.getBukkit().getUniqueId(), entry);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("runtime entryのtest fixture作成に失敗しました", exception);
        }
    }

    private record RuntimeHarness(DamageService damageService, SkillEffectService effects) {
    }
}
