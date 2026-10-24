package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.HunterFadeShotExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.HunterArrowRainExecutor;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Calls the real target mark store and executor multiplier, without starting a server or reading filebase. */
public final class RangedRuntimeGoldenHarness {
    private RangedRuntimeGoldenHarness() { }

    public static void main(String[] args) throws Exception {
        int[] tick = {0};
        Server server = mock(Server.class);
        when(server.getCurrentTick()).thenAnswer(ignored -> tick[0]);
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
        Player bukkit = mock(Player.class);
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID victim = UUID.fromString("00000000-0000-0000-0000-000000000201");
        when(bukkit.getUniqueId()).thenReturn(owner);
        AstPlayer player = mock(AstPlayer.class);
        when(player.getBukkit()).thenReturn(bukkit);
        AstEntity target = mock(AstEntity.class);
        when(target.isMob()).thenReturn(true);
        when(target.id()).thenReturn(victim);
        PassiveSkillService passives = mock(PassiveSkillService.class);
        when(passives.isPassiveSkillActive(player, PhantomArcherPassiveExecutor.CURSE_ID)).thenReturn(true);
        SkillService skillService = mock(SkillService.class);
        SkillRegistry registry = mock(SkillRegistry.class);
        when(skillService.registry()).thenReturn(registry);
        SkillDefinition curse = mock(SkillDefinition.class);
        when(curse.getParams()).thenReturn(Map.of("markBonusRatio", .25));
        when(registry.getDefinition(PhantomArcherPassiveExecutor.CURSE_ID)).thenReturn(curse);
        PhantomArcherRuntime.configure(passives, skillService, null, null, null, null);

        List<Integer> markTrace = new ArrayList<>();
        for (int stage : new int[] {0, 10, 20, 30}) {
            tick[0] = stage;
            PhantomArcherRuntime.addMark(player, target);
            markTrace.add(PhantomArcherRuntime.marks(owner, victim));
        }
        tick[0] = 149; markTrace.add(PhantomArcherRuntime.marks(owner, victim));
        tick[0] = 150; markTrace.add(PhantomArcherRuntime.marks(owner, victim));
        PhantomArcherRuntime.addMark(player, target);
        markTrace.add(PhantomArcherRuntime.marks(owner, victim));
        PhantomArcherRuntime.consume(owner, victim);
        markTrace.add(PhantomArcherRuntime.marks(owner, victim));
        System.out.println("{\"id\":\"phantom_marks\",\"marks\":" + markTrace + "}");

        var combat = mock(SkillCombatService.class);
        when(combat.hasCondition(target, ConditionType.WEAKNESS)).thenReturn(true);
        var services = new ActiveSkillServices(null, combat, null, null, null, null, null, null, null);
        SkillDefinition soul = mock(SkillDefinition.class);
        when(soul.getId()).thenReturn("phantom_archer_soul_pierce");
        when(soul.getParams()).thenReturn(Map.of("damageRatio", 2.8, "markDamageRatio", .65, "conditionBonusRatio", .4));
        PlayerSkillCaster caster = new PlayerSkillCaster(player);
        SkillCastContext source = new SkillCastContext(soul, caster, null, List.of(), null, null,
                SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH);
        var context = new PlayerActiveSkillContext(source, caster, services);
        Method ratio = PhantomArcherSkillExecutor.class.getDeclaredMethod("damageRatio", PlayerActiveSkillContext.class,
                AstEntity.class, SkillParamReader.class);
        ratio.setAccessible(true);
        var soulExecutor = new PhantomArcherSkillExecutor("soul_pierce", services);
        var eclipseExecutor = new PhantomArcherSkillExecutor("eclipse_shot", services);
        PhantomArcherRuntime.addMark(player, target);
        PhantomArcherRuntime.addMark(player, target);
        double soulRatio = (double) ratio.invoke(soulExecutor, context, target, context.params());
        double eclipseRatio = (double) ratio.invoke(eclipseExecutor, context, target, context.params());
        System.out.printf(Locale.ROOT, "{\"id\":\"phantom_ratios\",\"soul\":%.17g,\"eclipse\":%.17g}%n", soulRatio, eclipseRatio);

        var directions = HunterFadeShotExecutor.pelletDirections(new Vector(0, 0, 1), 5, 30);
        System.out.print("{\"id\":\"pellet_directions\",\"directions\":[");
        for (int index = 0; index < directions.size(); index++) {
            if (index > 0) System.out.print(",");
            Vector direction = directions.get(index);
            System.out.printf(Locale.ROOT, "[%.17g,%.17g,%.17g]", direction.getX(), direction.getY(), direction.getZ());
        }
        System.out.println("]}");
        Method solve = HunterArrowRainExecutor.class.getDeclaredMethod("solveBallisticVelocity", Location.class, Location.class, int.class, double.class);
        Method path = HunterArrowRainExecutor.class.getDeclaredMethod("ballisticPathLength", Vector.class, int.class, double.class);
        solve.setAccessible(true); path.setAccessible(true);
        Vector velocity = (Vector) solve.invoke(null, new Location(null, 0, 6.62, 0), new Location(null, 3, .05, 8), 12, .14);
        double pathLength = (double) path.invoke(null, velocity, 12, .14);
        System.out.printf(Locale.ROOT, "{\"id\":\"rain_ballistic\",\"velocity\":[%.17g,%.17g,%.17g],\"length\":%.17g}%n",
                velocity.getX(), velocity.getY(), velocity.getZ(), pathLength);
    }
}
