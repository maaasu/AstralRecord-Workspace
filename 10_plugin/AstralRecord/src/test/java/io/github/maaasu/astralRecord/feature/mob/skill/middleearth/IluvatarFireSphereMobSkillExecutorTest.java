package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IluvatarFireSphereMobSkillExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 6. 万象の聖域の専用契約
     * 検証契約: 反射火球はプレイヤーhitbox命中時に炎属性魔法ダメージを与え、60 tickの燃焼要求を発行する。
     */
    @Test
    void appliesFireDamageAndBurningOnSphereHit() {
        PluginMock plugin = MockBukkit.createMockPlugin("IluvatarFireSphereMobSkillExecutorTest");
        MobService mobService = mock(MobService.class);
        MobInstance caster = mock(MobInstance.class);
        UUID casterId = UUID.randomUUID();
        when(caster.instanceId()).thenReturn(casterId);
        when(mobService.plugin()).thenReturn(plugin);
        when(mobService.getInstance(casterId)).thenReturn(caster);

        World world = mock(World.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(world.getPlayers()).thenReturn(List.of(player));
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble())).thenReturn(null);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.getBoundingBox()).thenReturn(new BoundingBox(
                0.75D, -0.5D, -0.5D,
                1.25D, 1.5D, 0.5D
        ));

        DamageService damageService = mock(DamageService.class);
        AstEntity target = mock(AstEntity.class);
        when(damageService.resolveEntity(player)).thenReturn(target);
        when(damageService.attack(
                any(AstEntity.class), eq(target), eq(AttackType.MAGIC), anyList(), eq(DamageSource.SKILL)
        )).thenReturn(new DamageResult(10.0D));
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        ConditionService conditionService = mock(ConditionService.class);
        MobProjectileService projectileService = new MobProjectileService(
                mobService, mock(ParticleDisplayService.class)
        );

        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(playerId)).thenReturn(astPlayer);
            projectileService.launchBouncingFireSphere(
                    caster,
                    new Location(world, 0.0D, 0.0D, 0.0D),
                    new Vector(1.0D, 0.0D, 0.0D),
                    1.0D,
                    0.0D,
                    0.65D,
                    60L,
                    damageService,
                    conditionService
            );
            server().getScheduler().performTicks(2L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DamageComponent>> components = ArgumentCaptor.forClass(List.class);
            verify(damageService).attack(
                    any(AstEntity.class), eq(target), eq(AttackType.MAGIC), components.capture(), eq(DamageSource.SKILL)
            );
            assertEquals(DamageElement.FIRE, components.getValue().getFirst().element());
            assertEquals(0.65D, components.getValue().getFirst().ratio(), 0.0001D);

            ArgumentCaptor<ConditionApplyRequest> condition = ArgumentCaptor.forClass(ConditionApplyRequest.class);
            verify(conditionService).applyCondition(condition.capture());
            assertEquals(ConditionType.BURNING, condition.getValue().type());
            assertEquals(60L, condition.getValue().durationTicks());
        }
        projectileService.stop();
    }
}
