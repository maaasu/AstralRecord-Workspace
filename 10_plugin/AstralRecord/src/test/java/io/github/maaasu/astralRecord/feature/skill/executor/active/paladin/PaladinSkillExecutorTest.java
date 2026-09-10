package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.*;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.status.model.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaladinSkillExecutorTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 4. 葬送と聖域
     * 検証契約: 対象が存在しない場合は防護を消費せず、対象ありの場合だけ自分の残量を上限付き攻撃へ変換する。
     */
    @Test
    void requiemPreservesWardOnMissAndCapsConversion() {
        var f = new Fixture("paladin_requiem", Map.of("radius",5.0,"height",3.0,"maxTargets",6,
                "damageRatio",.5,"wardPerRatio",100.0,"bonusRatioCap",2.0));
        var executor = new PaladinRequiemExecutor(f.services);
        f.temporary.grantWard(f.id, 1000, 200);
        assertFalse(executor.cast(f.context).success());
        assertEquals(1000, f.temporary.wardAmount(f.id));
        when(f.targeting.inCone(any(), anyDouble(), anyDouble(), anyInt(), eq(true), eq(3.0))).thenReturn(List.of(f.target));
        assertTrue(executor.cast(f.context).success());
        assertEquals(0, f.temporary.wardAmount(f.id));
        verify(f.combat).hit(any(), same(f.target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(2.5));
        assertFalse(executor.cast(f.context).success());
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 2. 防御低下
     * 検証契約: 回避には聖痕が付かず、防御でダメージ0となった命中には聖痕が付く。
     */
    @Test
    void anathemaDistinguishesEvasionFromArmorBlock() {
        var f = new Fixture("paladin_anathema", Map.of("radius",5.0,"height",3.0,"maxTargets",8,
                "damageRatio",.6,"defenseReductionPercent",25.0,"durationTicks",120));
        var executor = new PaladinAnathemaExecutor(f.services);
        when(f.targeting.inCone(any(), anyDouble(), anyDouble(), anyInt(), eq(true), eq(3.0))).thenReturn(List.of(f.target));
        when(f.combat.hit(any(), same(f.target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(.6)))
                .thenReturn(DamageResult.evaded(50,100,50), new DamageResult(0));
        executor.cast(f.context);
        assertEquals(1, f.temporary.defenseMultiplier(f.target.id()));
        executor.cast(f.context);
        assertEquals(.75, f.temporary.defenseMultiplier(f.target.id()), 1e-9);
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 3. 時限シールドと聖歌
     * 検証契約: 非タンクの味方にも支援力と最大シールドを使った時限シールドを付け、聖歌は未付与時失敗する。
     */
    @Test
    void wardUsesCasterSupportAndCovenantRequiresWard() {
        var f = new Fixture("paladin_vesper_aegis", Map.of("radius",8.0,"height",4.0,"wardBase",30.0,
                "shieldRatio",.3,"supportRatio",.8,"durationTicks",160));
        when(f.snapshot.getMaxValue(StatusType.MAX_SHIELD)).thenReturn(100.0);
        when(f.snapshot.getMaxValue(StatusType.SUPPORT_POWER)).thenReturn(50.0);
        when(f.targeting.partyInRadius(f.astPlayer, 8, 4)).thenReturn(List.of(f.astPlayer));
        assertTrue(new PaladinVesperAegisExecutor(f.services).cast(f.context).success());
        assertEquals(100, f.temporary.wardAmount(f.id), 1e-9);
        var empty = new Fixture("paladin_covenant", Map.of("radius",8.0,"height",4.0,
                "outgoingMultiplier",1.2,"durationTicks",120));
        when(empty.targeting.partyInRadius(empty.astPlayer,8,4)).thenReturn(List.of(empty.astPlayer));
        assertFalse(new PaladinCovenantExecutor(empty.services).cast(empty.context).success());
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 5. 表示・運用
     * 検証契約: 防御低下の非有限値や上限超過、非整数tickを登録時に拒否する。
     */
    @Test
    void rejectsUnsafeDefinitionParameters() {
        var params = new HashMap<String,Object>(Map.of("radius",5.0,"height",3.0,"maxTargets",8,
                "damageRatio",.6,"defenseReductionPercent",51.0,"durationTicks",120));
        var f = new Fixture("paladin_anathema", params);
        var executor = new PaladinAnathemaExecutor(f.services);
        assertThrows(SkillParameterException.class, () -> executor.validateParams(f.context.skill()));
        params.put("defenseReductionPercent", Double.NaN);
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition("paladin_anathema", params)));
        params.put("defenseReductionPercent", 25);
        params.put("durationTicks", 1.5);
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition("paladin_anathema", params)));
    }

    private static SkillDefinition definition(String id, Map<String,Object> params) {
        return new SkillDefinition(id,id,"聖騎士",null,"SHIELD",List.of(),120L,0.0,0L,1,null,params,
                List.of("active"),SkillKind.ACTIVE,true,SkillResourceType.ENERGY,12.0);
    }

    private static class Fixture {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final AstPlayer astPlayer = mock(AstPlayer.class);
        final AstEntity target = mock(AstEntity.class);
        final SkillTargetingService targeting = mock(SkillTargetingService.class);
        final SkillCombatService combat = mock(SkillCombatService.class);
        final TemporarySkillEffectService temporary = new TemporarySkillEffectService();
        final StatusSnapshot snapshot = mock(StatusSnapshot.class);
        final ActiveSkillServices services = new ActiveSkillServices(targeting,combat,mock(SkillEffectService.class),
                mock(SkillProjectileService.class),mock(SkillMovementService.class),temporary,mock(SkillTaskService.class));
        final SkillCastContext context;
        Fixture(String skillId, Map<String,Object> params) {
            World world = mock(World.class);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(new Location(world,0,64,0));
            when(player.getEyeLocation()).thenReturn(new Location(world,0,65.6,0));
            when(astPlayer.getBukkit()).thenReturn(player);
            when(astPlayer.getStatusSnapshot()).thenReturn(snapshot);
            when(target.id()).thenReturn(UUID.randomUUID());
            when(target.location()).thenReturn(new Location(world,0,64,3));
            context = new SkillCastContext(definition(skillId,params),new PlayerSkillCaster(astPlayer),null,List.of(),
                    player.getEyeLocation(),snapshot,SkillCastTrigger.PLAYER_COMMAND,Instant.EPOCH);
        }
    }
}
