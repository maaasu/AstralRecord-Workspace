package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillProjectileServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: 最初のMob衝突はENTITY終端として通知し、衝突地点だけを範囲攻撃の起点にできる。
     */
    @Test
    void terminatesAsEntityAtFirstMobCollision() {
        Fixture fixture = fixture();
        AstEntity target = mock(AstEntity.class);
        Location impact = new Location(null, 0.8D, 1.0D, 0.0D);
        when(target.id()).thenReturn(UUID.randomUUID());
        when(target.location()).thenReturn(new Location(null, 0.8D, 0.0D, 0.0D));
        when(fixture.targeting.inLine(any(), any(), any(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(target));

        AtomicReference<AstEntity> hitTarget = new AtomicReference<>();
        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();
        fixture.service.launchWithTermination(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), spec(),
                (hit, ignored) -> hitTarget.set(hit), termination::set
        );

        assertSame(target, hitTarget.get());
        assertEquals(SkillProjectileTermination.Type.ENTITY, termination.get().type());
        assertEquals(impact, termination.get().location());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: 地形Block衝突はBLOCK終端と正確なBlock衝突地点を通知する。
     */
    @Test
    void terminatesAsBlockAtTerrainCollision() {
        Fixture fixture = fixture();
        Location blockImpact = new Location(null, 0.9D, 0.0D, 0.0D);
        when(fixture.targeting.blockImpact(any(), any(), anyDouble())).thenReturn(blockImpact);
        when(fixture.targeting.clippedEnd(any(), any(), anyDouble()))
                .thenReturn(new Location(null, 0.8D, 0.0D, 0.0D));

        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();
        fixture.service.launchWithTermination(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), spec(),
                (target, ignored) -> { }, termination::set
        );

        assertEquals(SkillProjectileTermination.Type.BLOCK, termination.get().type());
        assertEquals(blockImpact, termination.get().location());
        assertEquals(new Location(null, 0.8D, 0.0D, 0.0D), termination.get().effectLocation());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: Block面より手前のMobは短縮した表示終点にかかわらずENTITY終端とし、Block面と同距離ならBLOCKを優先する。
     */
    @Test
    void terminatesAsEntityForMobBeforeBlockImpactPlane() {
        Fixture fixture = fixture();
        Location blockImpact = new Location(null, 0.9D, 0.0D, 0.0D);
        Location safeEnd = new Location(null, 0.8D, 0.0D, 0.0D);
        AstEntity target = mock(AstEntity.class);
        when(target.id()).thenReturn(UUID.randomUUID());
        when(target.location()).thenReturn(new Location(null, 0.85D, 0.0D, 0.0D));
        when(fixture.targeting.blockImpact(any(), any(), anyDouble())).thenReturn(blockImpact);
        when(fixture.targeting.clippedEnd(any(), any(), anyDouble())).thenReturn(safeEnd);
        when(fixture.targeting.inLineBeforeBlock(any(), any(), any(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(target));

        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();
        fixture.service.launchWithTermination(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), spec(),
                (hit, ignored) -> { }, termination::set
        );

        assertEquals(SkillProjectileTermination.Type.ENTITY, termination.get().type());
        verify(fixture.targeting).inLineBeforeBlock(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), 0.9D, 0.45D, 1
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: 最大射程まで無衝突ならRANGE終端となり、着弾爆発を起こす契機と区別できる。
     */
    @Test
    void terminatesAsRangeWithoutCollision() {
        Fixture fixture = fixture();
        Location end = new Location(null, 1.0D, 0.0D, 0.0D);
        when(fixture.targeting.clippedEnd(any(), any(), anyDouble())).thenReturn(end);

        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();
        fixture.service.launchWithTermination(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), spec(),
                (target, ignored) -> { }, termination::set
        );

        assertEquals(SkillProjectileTermination.Type.RANGE, termination.get().type());
        assertEquals(end, termination.get().location());
    }

    private static Fixture fixture() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        Player player = mock(Player.class);
        Location origin = mock(Location.class);
        when(origin.clone()).thenReturn(origin);
        when(origin.distance(any(Location.class))).thenAnswer(invocation ->
                invocation.getArgument(0, Location.class).getX());
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(targeting.clippedEnd(any(), any(), anyDouble()))
                .thenReturn(new Location(null, 1.0D, 0.0D, 0.0D));
        when(targeting.inLine(any(), any(), any(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(5, IntConsumer.class).accept(0);
            return null;
        }).when(tasks).repeat(any(UUID.class), anyString(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class));
        return new Fixture(new SkillProjectileService(targeting, effects, tasks), targeting, player, origin);
    }

    private static SkillProjectileSpec spec() {
        return new SkillProjectileSpec(
                1.0D, 1.0D, 0.45D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
    }

    private record Fixture(
            SkillProjectileService service,
            SkillTargetingService targeting,
            Player player,
            Location origin
    ) {
    }
}
