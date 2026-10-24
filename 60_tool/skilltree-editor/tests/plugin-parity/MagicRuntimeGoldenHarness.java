package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ElementalPrismRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.MageFrostBlizzardExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.MageSparkingExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.WizardEmulateSparkExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Calls real Plugin geometry and passive runtime methods; no server or master files are read. */
public final class MagicRuntimeGoldenHarness {
    private MagicRuntimeGoldenHarness() { }

    public static void main(String[] args) throws Exception {
        prism(); vortex(); spiral(); bounce(); arcaneFlow(); meditation();
    }

    /** The production prism ray/sphere calculation is private, so reflection selects that exact method. */
    private static void prism() throws Exception {
        Method hit = ElementalPrismRuntimeService.class.getDeclaredMethod("firstSphereHit",
                Location.class, Vector.class, Location.class, double.class, double.class);
        hit.setAccessible(true);
        double distance = (double) hit.invoke(null, new Location(null, 0, 1.62, 0), new Vector(0, 0, 1),
                new Location(null, 0, 1.5, 3), 1.27D, 4.0D);
        double behind = (double) hit.invoke(null, new Location(null, 0, 1.62, 0), new Vector(0, 0, -1),
                new Location(null, 0, 1.5, 3), 1.27D, 4.0D);
        System.out.printf(Locale.ROOT, "{\"id\":\"magic_prism_intersection\",\"distance\":%.17g,\"behindMiss\":%s}%n", distance, Double.isNaN(behind));
    }

    /** Both the ordinary and the zero-offset fallback path execute Plugin bytecode. */
    private static void vortex() throws Exception {
        Method method = MageFrostBlizzardExecutor.class.getDeclaredMethod("vortexVelocity",
                Location.class, Location.class, Vector.class, double.class, double.class, double.class);
        method.setAccessible(true);
        Vector ordinary = (Vector) method.invoke(null, new Location(null, 0, 0, 0), new Location(null, 3, 2, 4),
                new Vector(0, 0, 1), .16D, .08D, .04D);
        Vector fallback = (Vector) method.invoke(null, new Location(null, 0, 0, 0), new Location(null, 0, 2, 0),
                new Vector(0, 1, 0), .16D, .08D, .04D);
        System.out.printf(Locale.ROOT, "{\"id\":\"magic_vortex_velocity\",\"ordinary\":%s,\"fallback\":%s}%n", vector(ordinary), vector(fallback));
    }

    /** Captures five successive real SparkState.advanceSpiral displacements, including yaw. */
    private static void spiral() throws Exception {
        Method create = MageSparkingExecutor.class.getDeclaredMethod("spiralStates", Location.class, int.class, float.class);
        create.setAccessible(true);
        List<?> states = (List<?>) create.invoke(null, new Location(null, 0, 0, 0), 5, 30.0F);
        Object state = states.getFirst();
        Method advance = state.getClass().getDeclaredMethod("advanceSpiral", double.class, double.class);
        advance.setAccessible(true);
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) output.append(',');
            output.append(vector((Vector) advance.invoke(state, .14D, Math.toRadians(3.0D))));
        }
        System.out.printf("{\"id\":\"magic_spark_spiral\",\"steps\":[%s]}%n", output);
    }

    /** The floor-reflection method preserves horizontal direction and resets the vertical speed. */
    private static void bounce() throws Exception {
        Class<?> stateType = Class.forName(WizardEmulateSparkExecutor.class.getName() + "$SparkState");
        Constructor<?> constructor = stateType.getDeclaredConstructor(Location.class, Vector.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance(new Location(null, 0, 1.62, .35), new Vector(0, 0, 1));
        Method reflection = WizardEmulateSparkExecutor.class.getDeclaredMethod("reflectAtBlock", stateType, Vector.class, double.class);
        reflection.setAccessible(true);
        reflection.invoke(null, state, new Vector(0, 1, 0), Math.sqrt(2.0D * .04D * (1.3D - .05D)));
        Field vertical = stateType.getDeclaredField("verticalSpeed"); vertical.setAccessible(true);
        System.out.printf(Locale.ROOT, "{\"id\":\"magic_emulate_bounce\",\"verticalSpeed\":%.17g}%n", vertical.getDouble(state));
    }

    /** A nonmagic prior success still enables Arcane Flow for the next different magic skill. */
    private static void arcaneFlow() {
        var runtime = new ArcaneFlowSkillRuntimeService(mock(ParticleDisplayService.class));
        AstPlayer player = player();
        SkillDefinition binding = definition("mage_arcane_flow", Map.of("castTimeReductionPercent", 5.0D));
        runtime.activate(new PassiveSkillContext(player, binding, Instant.EPOCH, 0));
        SkillDefinition prior = definition("physical", Map.of());
        when(prior.getTags()).thenReturn(List.of()); runtime.onSkillCast(player, prior);
        SkillDefinition next = definition("magic", Map.of()); when(next.getTags()).thenReturn(List.of("magic"));
        SkillDefinition same = definition("physical", Map.of()); when(same.getTags()).thenReturn(List.of("magic"));
        System.out.printf(Locale.ROOT, "{\"id\":\"magic_arcane_history\",\"different\":%.17g,\"same\":%.17g}%n",
                runtime.castTimeReductionPercent(player, next), runtime.castTimeReductionPercent(player, same));
    }

    /** Seeds only the runtime's activated-state boundary; all multiplier/window decisions are production methods. */
    @SuppressWarnings("unchecked")
    private static void meditation() throws Exception {
        var runtime = new MeditationSkillRuntimeService(mock(StatusService.class), mock(ParticleDisplayService.class));
        AstPlayer player = player();
        SkillDefinition skill = definition("adventurer_meditation", Map.of("initialRegenMultiplier", 2.0D,
                "regenMultiplierIncrement", .5D, "activeDurationTicks", 140));
        Class<?> stateType = Class.forName(MeditationSkillRuntimeService.class.getName() + "$State");
        Constructor<?> constructor = stateType.getDeclaredConstructor(); constructor.setAccessible(true);
        Object state = constructor.newInstance();
        Field active = stateType.getDeclaredField("effectActive"); active.setAccessible(true); active.setBoolean(state, true);
        Field started = stateType.getDeclaredField("regenStartedAtTick"); started.setAccessible(true); started.set(state, 60L);
        Field states = MeditationSkillRuntimeService.class.getDeclaredField("states"); states.setAccessible(true);
        ((Map<UUID, Object>) states.get(runtime)).put(player.getBukkit().getUniqueId(), state);
        StringBuilder output = new StringBuilder();
        for (long tick : new long[] { 59, 60, 79, 80, 199, 200 }) {
            if (!output.isEmpty()) output.append(',');
            output.append(String.format(Locale.ROOT, "%.17g", runtime.resourceRegenMultiplier(new PassiveSkillContext(player, skill, Instant.EPOCH, tick))));
        }
        System.out.printf("{\"id\":\"magic_meditation_multiplier\",\"values\":[%s]}%n", output);
    }

    private static AstPlayer player() {
        var bukkit = mock(Player.class); when(bukkit.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000211"));
        var player = mock(AstPlayer.class); when(player.getBukkit()).thenReturn(bukkit); return player;
    }
    private static SkillDefinition definition(String id, Map<String, Object> parameters) {
        var skill = mock(SkillDefinition.class); when(skill.getId()).thenReturn(id); when(skill.getParams()).thenReturn(parameters); return skill;
    }
    private static String vector(Vector value) {
        return String.format(Locale.ROOT, "[%.17g,%.17g,%.17g]", value.getX(), value.getY(), value.getZ());
    }
}
