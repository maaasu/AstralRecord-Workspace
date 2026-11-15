package io.github.maaasu.astralRecord.feature.skill.executor.active.support;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster.SwordmasterBladeTempestExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster.SwordmasterCrescentDuetExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanFlameRushExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.IntConsumer;
import javax.tools.ToolProvider;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Calls real Plugin executors with one fixed mob and a virtual tick queue. Emits JSON Lines. */
public final class SkillExecutorGoldenHarness {
    private SkillExecutorGoldenHarness() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runWithVisualStub();
            return;
        }
        installRegistryProvider();
        installBukkitStub();
        run("swordsman_flame_rush", Map.of("range", 6.0, "targetAngle", 60.0, "maxTargets", 5,
                "damageRatios", List.of(.78, .90), "secondHitDelayTicks", 4,
                "burningChance", 0.0, "burningDurationTicks", 100));
        run("swordmaster_blade_tempest", Map.of("radius", 3.0, "maxTargets", 5,
                "damageRatio", 1.5, "hitCount", 4, "intervalTicks", 4));
        run("swordmaster_crescent_duet", Map.of("range", 4.5, "angle", 65.0, "maxTargets", 5,
                "damageRatio", 2.0, "criticalMultiplier", 1.6, "intervalTicks", 4));
    }

    /** Keeps Plugin executor bytecode real; only the Bukkit-only visual constant table is shadowed. */
    private static void runWithVisualStub() throws Exception {
        Path stubRoot = Files.createTempDirectory("skill-parity-visuals-");
        Path source = stubRoot.resolve("src/io/github/maaasu/astralRecord/shared/effect/SharedParticleDefinitions.java");
        Files.createDirectories(source.getParent());
        String fields = String.join("\n", Arrays.stream(new String[] {
                "SWORDMASTER_GOLD", "SWORDMASTER_VIOLET", "SWORDMASTER_SILVER",
                "SWORDSMAN_FLAME_RUSH_HORIZONTAL_DUST", "SWORDSMAN_FLAME_RUSH_HORIZONTAL_FLAME",
                "SWORDSMAN_FLAME_RUSH_VERTICAL_DUST", "SWORDSMAN_FLAME_RUSH_VERTICAL_FLAME",
                "SKILL_MAGE_FIRE"
        }).map(name -> "public static final SharedParticleDefinition " + name + " = null;").toList());
        Files.writeString(source, "package io.github.maaasu.astralRecord.shared.effect;\n"
                + "public final class SharedParticleDefinitions {\n" + fields + "\n}\n");
        String classpath = System.getProperty("java.class.path");
        int compilation = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-cp", classpath, "-d", stubRoot.toString(), source.toString());
        if (compilation != 0) throw new IllegalStateException("Visual stub compilation failed: " + compilation);
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+EnableDynamicAgentLoading", "-cp", stubRoot + System.getProperty("path.separator") + classpath,
                SkillExecutorGoldenHarness.class.getName(), "--child").inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("Executor harness failed: " + exit);
    }

    private static void installBukkitStub() throws Exception {
        var server = mock(Server.class);
        when(server.createBlockData(any(Material.class))).thenReturn(mock(BlockData.class));
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }

    /** Paper's Sound constants need a registry; rendering is mocked, so values can be null. */
    private static void installRegistryProvider() throws Exception {
        Path root = Path.of(SkillExecutorGoldenHarness.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        Path provider = root.resolve("META-INF/services/" + RegistryAccess.class.getName());
        Files.createDirectories(provider.getParent());
        Files.writeString(provider, RegistryAccessStub.class.getName() + System.lineSeparator());
    }

    public static final class RegistryAccessStub implements RegistryAccess {
        public RegistryAccessStub() { }

        @SuppressWarnings("unchecked")
        private static <T extends Keyed> Registry<T> registry() {
            return (Registry<T>) Proxy.newProxyInstance(Registry.class.getClassLoader(),
                    new Class<?>[] { Registry.class }, (proxy, method, arguments) -> null);
        }

        @SuppressWarnings("removal") // Required by Paper's RegistryAccess interface used by this isolated harness.
        @Override public <T extends Keyed> Registry<T> getRegistry(Class<T> type) { return registry(); }
        @Override public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) { return registry(); }
    }

    private static void run(String id, Map<String, Object> params) throws Exception {
        int[] tick = {0};
        var hitEvents = new ArrayList<String>();
        var queue = new TreeMap<Long, List<Runnable>>();
        var world = mock(World.class);
        var player = mock(Player.class);
        var location = new Location(world, 0, 64, 0);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(location.clone());
        when(player.getLocation()).thenReturn(location.clone());
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000101"));
        when(player.isOnline()).thenReturn(true);

        var astPlayer = mock(AstPlayer.class);
        var snapshot = mock(StatusSnapshot.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getStatusSnapshot()).thenReturn(snapshot);
        when(snapshot.getCurrentHp()).thenReturn(100.0);
        var caster = new PlayerSkillCaster(astPlayer);
        var skill = mock(SkillDefinition.class);
        when(skill.getId()).thenReturn(id);
        when(skill.getParams()).thenReturn(params);
        var source = new SkillCastContext(skill, caster, null, List.of(), location.clone(), snapshot,
                SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH);

        var mob = mock(AstEntity.class);
        when(mob.isMob()).thenReturn(true);
        when(mob.currentHealth()).thenReturn(100.0);
        when(mob.location()).thenReturn(new Location(world, 0, 64, 2));

        var targeting = mock(SkillTargetingService.class);
        when(targeting.inCone(any(), anyDouble(), anyDouble(), anyInt(), anyBoolean())).thenReturn(List.of(mob));
        when(targeting.inSphere(any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn(List.of(mob));
        var combat = mock(SkillCombatService.class, invocation -> {
            if (!invocation.getMethod().getName().equals("hit")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            Object[] arguments = invocation.getArguments();
            for (int i = 0; i + 1 < arguments.length; i++) {
                if (arguments[i] instanceof DamageElement element && arguments[i + 1] instanceof Double ratio) {
                    hitEvents.add(String.format(Locale.ROOT,
                            "{\"tick\":%d,\"ratio\":%.17g,\"element\":\"%s\"}", tick[0], ratio, element));
                    break;
                }
            }
            return new DamageResult(1.0, id.equals("swordmaster_crescent_duet"));
        });
        var tasks = mock(SkillTaskService.class);
        doAnswer(invocation -> {
            long delay = invocation.getArgument(2);
            Runnable action = invocation.getArgument(3);
            queue.computeIfAbsent((long) tick[0] + delay, ignored -> new ArrayList<>()).add(action);
            return null;
        }).when(tasks).later(any(), anyString(), anyLong(), any());
        doAnswer(invocation -> {
            long delay = invocation.getArgument(2);
            long period = invocation.getArgument(3);
            int count = invocation.getArgument(4);
            IntConsumer action = invocation.getArgument(5);
            for (int index = 0; index < count; index++) {
                int frame = index;
                queue.computeIfAbsent((long) tick[0] + delay + period * index,
                        ignored -> new ArrayList<>()).add(() -> action.accept(frame));
            }
            return null;
        }).when(tasks).repeat(any(), anyString(), anyLong(), anyLong(), anyInt(), any());

        var services = new ActiveSkillServices(targeting, combat, mock(SkillEffectService.class),
                mock(SkillProjectileService.class), mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class), tasks,
                mock(ElementalPrismRuntimeService.class), mock(SkillMagicCircleRegistry.class));
        Object executor = switch (id) {
            case "swordsman_flame_rush" -> new SwordsmanFlameRushExecutor(services);
            case "swordmaster_blade_tempest" -> new SwordmasterBladeTempestExecutor(services);
            case "swordmaster_crescent_duet" -> new SwordmasterCrescentDuetExecutor(services);
            default -> throw new IllegalArgumentException(id);
        };
        Method castPlayer = executor.getClass().getDeclaredMethod("castPlayer", PlayerActiveSkillContext.class);
        castPlayer.setAccessible(true);
        castPlayer.invoke(executor, new PlayerActiveSkillContext(source, caster, services));
        while (!queue.isEmpty()) {
            var entry = queue.pollFirstEntry();
            tick[0] = entry.getKey().intValue();
            for (Runnable action : entry.getValue()) action.run();
        }
        System.out.printf("{\"id\":\"%s\",\"hits\":[%s]}%n", id, String.join(",", hitEvents));
    }
}
