package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.skill.active.service.*;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.skill.service.SeijakuIssenSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;

import static org.mockito.Mockito.*;

/** Calls production resource/runtime methods; no damage formula is duplicated in this harness. */
public final class MeleeRuntimeGoldenHarness {
    private MeleeRuntimeGoldenHarness() { }

    public static void main(String[] args) throws Exception {
        guardGain();
        guardDecay();
        riposteReserve();
    }

    private static void guardGain() {
        double[][] inputs = {{100, 20, 100}, {100, 0, 100}, {0, 20, 100}, {-1, -3, 0}, {100, 150, 1000}};
        for (int index = 0; index < inputs.length; index++) {
            var v = inputs[index];
            System.out.printf(Locale.ROOT,
                "{\"id\":\"guard_gain_%d\",\"raw\":%.17g,\"health\":%.17g,\"maximumHealth\":%.17g,\"gain\":%.17g}%n",
                index, v[0], v[1], v[2], PaladinGuardRuntimeService.calculateGain(v[0], v[1], v[2]));
        }
    }

    private static void guardDecay() throws Exception {
        Class<?> type = Arrays.stream(PaladinGuardRuntimeService.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("GuardState")).findFirst().orElseThrow();
        var constructor = type.getDeclaredConstructor(double.class, double.class, long.class, boolean.class, double.class, long.class);
        constructor.setAccessible(true);
        var decay = type.getDeclaredMethod("decay", long.class); decay.setAccessible(true);
        var current = type.getDeclaredMethod("current"); current.setAccessible(true);
        var withCurrent = type.getDeclaredMethod("withCurrent", double.class); withCurrent.setAccessible(true);
        Object state = constructor.newInstance(10.0, 10.0, 0L, false, 0.0, 0L);
        state = decay.invoke(state, 9_999L); double before = (double) current.invoke(state);
        state = decay.invoke(state, 10_000L); double first = (double) current.invoke(state);
        state = decay.invoke(state, 11_000L); double second = (double) current.invoke(state);
        state = withCurrent.invoke(state, 2.0);
        state = decay.invoke(state, 12_000L); double afterSpend = (double) current.invoke(state);
        System.out.printf(Locale.ROOT,
            "{\"id\":\"guard_decay\",\"before\":%.17g,\"first\":%.17g,\"second\":%.17g,\"afterSpend\":%.17g}%n",
            before, first, second, afterSpend);
    }

    private static void riposteReserve() throws Exception {
        var plugin = mock(Plugin.class); when(plugin.getName()).thenReturn("MeleeParity"); when(plugin.namespace()).thenReturn("meleeparity");
        var runtime = new SeijakuIssenSkillRuntimeService(plugin, mock(SkillService.class), mock(SkillCombatService.class),
            mock(SkillTargetingService.class), mock(SkillEffectService.class), mock(SkillTaskService.class), mock(PlayerHudService.class));
        var player = mock(Player.class); var world = mock(World.class); var ast = mock(AstPlayer.class); var status = mock(StatusSnapshot.class);
        when(player.getUniqueId()).thenReturn(new UUID(0, 101)); when(player.getWorld()).thenReturn(world); when(player.isOnline()).thenReturn(true);
        when(world.getUID()).thenReturn(new UUID(0, 102)); when(ast.getBukkit()).thenReturn(player); when(ast.getStatusSnapshot()).thenReturn(status);
        when(status.getCurrentHp()).thenReturn(100.0);
        var skill = mock(SkillDefinition.class); when(skill.getId()).thenReturn("swordmaster_seijaku_issen");
        when(skill.getParams()).thenReturn(Map.of("counterTicks", 1, "counterDamageRatio", 3.9, "energyRecoveryRatio", .1,
            "counterSweepRange", 5.5, "counterSweepMaxTargets", 5, "maxRiposteStacks", 5, "counterDamageBonusPerStack", .1));
        int unboundGain = runtime.addRiposte(ast, 10);
        runtime.activate(new PassiveSkillContext(ast, skill, Instant.EPOCH, 0));
        int gained = runtime.addRiposte(ast, 10); int duplicate = runtime.addRiposte(ast, 10);
        int stacks = runtime.riposteStacks(ast); boolean consumed = runtime.consumeRiposte(ast); boolean empty = runtime.consumeRiposte(ast);
        System.out.printf(Locale.ROOT,
            "{\"id\":\"riposte_reserve\",\"unboundGain\":%d,\"gained\":%d,\"duplicateGain\":%d,\"stacks\":%d,\"consumed\":%s,\"emptyConsumed\":%s,\"remaining\":%d}%n",
            unboundGain, gained, duplicate, stacks, consumed, empty, runtime.riposteStacks(ast));
    }
}
