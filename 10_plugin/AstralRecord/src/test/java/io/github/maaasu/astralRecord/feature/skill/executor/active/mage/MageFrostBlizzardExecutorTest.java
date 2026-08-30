package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MageFrostBlizzardExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: 進行先にBlock面がある場合は0.55mの手前までだけ進み、その後のtickでも停止位置を維持する。
     */
    @Test
    void stopsBeforeBlockAndKeepsStoppedPosition() {
        World world = mock(World.class);
        MageFrostBlizzardExecutor.BlizzardState state = new MageFrostBlizzardExecutor.BlizzardState(
                new Location(world, 0.0D, 64.0D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                false
        );
        SkillTargetingService.BlockHit wall = new SkillTargetingService.BlockHit(
                new Location(world, 0.0D, 64.0D, 0.60D),
                new Vector(0.0D, 0.0D, -1.0D)
        );

        state.advance(0.18D, wall);
        Location stopped = state.center();
        state.advance(0.18D, null);

        assertEquals(0.05D, stopped.getZ(), 1.0E-9D);
        assertEquals(stopped, state.center());
        assertTrue(state.stopped());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: 中心の東側にいる対象へ、接線+Z・中心方向-X・上方向+Yを合成した基礎velocityを返す。
     */
    @Test
    void composesTangentialInwardAndVerticalVelocity() {
        Vector velocity = MageFrostBlizzardExecutor.vortexVelocity(
                new Location(null, 0.0D, 64.0D, 0.0D),
                new Location(null, 1.0D, 64.0D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                0.16D,
                0.08D,
                0.04D
        );

        assertEquals(-0.08D, velocity.getX(), 1.0E-9D);
        assertEquals(0.04D, velocity.getY(), 1.0E-9D);
        assertEquals(0.16D, velocity.getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.2 バランス・入手・演出・テスト契約
     * 検証契約: 竜巻particleは18点の上昇螺旋を構成し、最下点から最上点へ2.35m上昇する。
     */
    @Test
    void particleLocationsFormAscendingSpiral() {
        Location center = new Location(null, 4.0D, 70.0D, -2.0D);
        List<Location> points = MageFrostBlizzardExecutor.particleLocations(center, 12);

        assertEquals(18, points.size());
        assertEquals(center.getY() - 0.85D, points.getFirst().getY(), 1.0E-9D);
        assertEquals(center.getY() + 1.50D, points.getLast().getY(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: 200tick中、4tickのvelocity更新と10tickのdamage更新の和集合だけで最大8体を探索し、20回の24% ICE hitを行う。
     */
    @Test
    void advancesCombatForConfiguredDurationCadenceAndTargetLimit() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        ActiveSkillServices services = services(targeting, combat, effects, mock(SkillTaskService.class));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        World world = mock(World.class);
        Location castLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        PlayerSkillCaster caster = new PlayerSkillCaster(astPlayer);
        PlayerActiveSkillContext context = new PlayerActiveSkillContext(
                new SkillCastContext(
                        definition(), caster, null, List.of(), castLocation,
                        StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
                ),
                caster,
                services
        );
        List<AstEntity> targets = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            AstEntity target = mock(AstEntity.class);
            when(target.location()).thenReturn(new Location(world, index + 1.0D, 64.0D, 0.0D));
            targets.add(target);
        }
        when(targeting.inRadius(
                same(player), any(Location.class), anyDouble(), anyDouble(), anyInt(), eq(true)
        )).thenReturn(targets);
        MageFrostBlizzardExecutor executor = new MageFrostBlizzardExecutor(services);
        MageFrostBlizzardExecutor.BlizzardState state = new MageFrostBlizzardExecutor.BlizzardState(
                new Location(world, 0.0D, 64.0D, 0.0D), new Vector(0.0D, 0.0D, 1.0D), true
        );

        for (int tick = 0; tick < 200; tick++) {
            executor.advance(context, state, tick, 0.18D, 2.75D, 2.5D, 8,
                    0.24D, 10, 0.16D, 0.08D, 0.04D);
        }

        verify(targeting, times(60)).inRadius(
                same(player), any(Location.class), eq(2.75D), eq(2.5D), eq(8), eq(true)
        );
        verify(combat, times(480)).velocity(any(AstEntity.class), any(Vector.class));
        verify(combat, times(160)).hit(
                any(AstEntity.class), any(AstEntity.class), eq(AttackType.MAGIC), eq(DamageElement.ICE),
                eq(0.24D), any(ActiveSkillCondition[].class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.2 バランス・入手・演出・テスト契約
     * 検証契約: 7個の小型PACKED_ICE表示を生成し、destroyで全表示を除去する。
     */
    @Test
    void spawnsAndDestroysSevenPackedIceDisplays() {
        World world = mock(World.class);
        List<BlockDisplay> displays = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            BlockDisplay display = mock(BlockDisplay.class);
            when(display.isValid()).thenReturn(true);
            displays.add(display);
        }
        AtomicInteger nextDisplay = new AtomicInteger();
        when(world.spawn(
                any(Location.class), eq(BlockDisplay.class),
                org.mockito.ArgumentMatchers.<Consumer<? super BlockDisplay>>any()
        )).thenAnswer(invocation -> {
            BlockDisplay display = displays.get(nextDisplay.getAndIncrement());
            Consumer<BlockDisplay> initializer = invocation.getArgument(2);
            initializer.accept(display);
            return display;
        });
        MageFrostBlizzardExecutor.BlizzardState state = new MageFrostBlizzardExecutor.BlizzardState(
                new Location(world, 0.0D, 64.0D, 0.0D), new Vector(0.0D, 0.0D, 1.0D), false
        );

        state.spawnDisplays();
        state.destroy();

        verify(world, times(7)).spawn(
                any(Location.class), eq(BlockDisplay.class),
                org.mockito.ArgumentMatchers.<Consumer<? super BlockDisplay>>any()
        );
        for (BlockDisplay display : displays) {
            verify(display).setBlock(org.mockito.ArgumentMatchers.argThat(
                    blockData -> blockData.getMaterial() == Material.PACKED_ICE
            ));
            verify(display).remove();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: 表示生成後、task登録前の例外でも生成済みBlockDisplayを除去する。
     */
    @Test
    void castCleansDisplaysWhenRegistrationSetupThrows() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = services(targeting, combat, effects, tasks);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        World world = mock(World.class);
        List<BlockDisplay> displays = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            BlockDisplay display = mock(BlockDisplay.class);
            when(display.isValid()).thenReturn(true);
            displays.add(display);
        }
        AtomicInteger nextDisplay = new AtomicInteger();
        when(world.spawn(
                any(Location.class), eq(BlockDisplay.class),
                org.mockito.ArgumentMatchers.<Consumer<? super BlockDisplay>>any()
        )).thenAnswer(invocation -> {
            BlockDisplay display = displays.get(nextDisplay.getAndIncrement());
            Consumer<BlockDisplay> initializer = invocation.getArgument(2);
            initializer.accept(display);
            return display;
        });
        Location castLocation = new Location(world, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
        when(player.getEyeLocation()).thenReturn(castLocation);
        org.mockito.Mockito.doThrow(new IllegalStateException("sound failed"))
                .when(effects).sound(any(Location.class), any(), anyFloat(), anyFloat());

        assertThrows(IllegalStateException.class,
                () -> new MageFrostBlizzardExecutor(services).cast(new SkillCastContext(
                        definition(), new PlayerSkillCaster(astPlayer), null, List.of(), castLocation,
                        StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
                )));

        for (BlockDisplay display : displays) {
            verify(display).remove();
        }
        verify(tasks, never()).repeat(any(), any(), anyLong(), anyLong(), anyInt(), any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: 成功castは200回の反復callbackとBlockDisplay cleanupをtaskへ直接登録する。
     */
    @Test
    void successfulCastRegistersDurationCallbackAndDisplayCleanup() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = services(targeting, combat, effects, tasks);
        Player player = mock(Player.class);
        java.util.UUID playerId = java.util.UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        DisplayFixture displayFixture = displayFixture();
        Location castLocation = new Location(
                displayFixture.world(), 0.0D, 64.0D, 0.0D, 0.0F, 0.0F
        );
        when(player.getEyeLocation()).thenReturn(castLocation);

        new MageFrostBlizzardExecutor(services).cast(new SkillCastContext(
                definition(), new PlayerSkillCaster(astPlayer), null, List.of(), castLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        ));

        ArgumentCaptor<IntConsumer> callback = ArgumentCaptor.forClass(IntConsumer.class);
        ArgumentCaptor<Runnable> cleanup = ArgumentCaptor.forClass(Runnable.class);
        verify(tasks).repeat(
                eq(playerId), anyString(), eq(0L), eq(1L), eq(200), callback.capture(), cleanup.capture()
        );
        callback.getValue().accept(0);
        cleanup.getValue().run();
        verify(targeting).inRadius(same(player), any(Location.class), eq(2.75D), eq(2.5D), eq(8), eq(true));
        for (BlockDisplay display : displayFixture.displays()) {
            verify(display).remove();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: schedulerへのrepeat登録失敗時も生成済みBlockDisplayを除去し、例外を再throwする。
     */
    @Test
    void castCleansDisplaysWhenRepeatRegistrationThrows() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = services(targeting, combat, effects, tasks);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        DisplayFixture displayFixture = displayFixture();
        Location castLocation = new Location(
                displayFixture.world(), 0.0D, 64.0D, 0.0D, 0.0F, 0.0F
        );
        when(player.getEyeLocation()).thenReturn(castLocation);
        org.mockito.Mockito.doThrow(new IllegalStateException("schedule failed"))
                .when(tasks).repeat(any(), anyString(), anyLong(), anyLong(), anyInt(), any(), any());

        assertThrows(IllegalStateException.class,
                () -> new MageFrostBlizzardExecutor(services).cast(new SkillCastContext(
                        definition(), new PlayerSkillCaster(astPlayer), null, List.of(), castLocation,
                        StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
                )));

        for (BlockDisplay display : displayFixture.displays()) {
            verify(display).remove();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.2 バランス・入手・演出・テスト契約
     * 検証契約: MAGE_FROST_BLIZZARDはRGB(90,215,255)の水色DUSTである。
     */
    @Test
    void particleDefinitionUsesConfiguredCyanDust() {
        assertEquals(Particle.DUST, SharedParticleDefinitions.MAGE_FROST_BLIZZARD.particle());
        Particle.DustOptions dust = assertInstanceOf(
                Particle.DustOptions.class, SharedParticleDefinitions.MAGE_FROST_BLIZZARD.data()
        );
        assertEquals(Color.fromRGB(90, 215, 255), dust.getColor());
        assertEquals(1.15F, dust.getSize(), 1.0E-6F);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.2 バランス・入手・演出・テスト契約
     * 検証契約: 本番マスタはMP40・200tick・10tick間隔・24%・最大8体と指定iconを定義し、交換ショップが同じskillの仮想ジェムを参照する。
     */
    @Test
    void filebaseDefinesBalanceIconAndAcquisitionReferences() throws Exception {
        Path root = repositoryRoot();
        YamlConfiguration skill = YamlConfiguration.loadConfiguration(
                root.resolve("40_filebase/30.features.skill/v1.mage_frost_blizzard.yml").toFile()
        );

        assertEquals("DIAMOND_NAUTILUS_ARMOR", skill.getString("icon"));
        assertEquals(Material.DIAMOND_NAUTILUS_ARMOR, Material.matchMaterial(skill.getString("icon")));
        assertEquals(40.0D, skill.getDouble("resourceCost"), 1.0E-9D);
        assertEquals(200, skill.getInt("params.durationTicks"));
        assertEquals(10, skill.getInt("params.damageIntervalTicks"));
        assertEquals(0.24D, skill.getDouble("params.damageRatio"), 1.0E-9D);
        assertEquals(8, skill.getInt("params.maxTargets"));

        YamlConfiguration shop = YamlConfiguration.loadConfiguration(
                root.resolve("40_filebase/45.features.shop/v1.skill_gem_exchange.yml").toFile()
        );
        assertTrue(shop.getMapList("items").stream().anyMatch(item -> {
            Object itemId = item.get("itemId");
            return itemId instanceof Map<?, ?> ref
                    && "item:00_skill_gem_mage_frost_blizzard".equals(ref.get("ref"))
                    && Integer.valueOf(1).equals(item.get("page"))
                    && Integer.valueOf(15).equals(item.get("slot"));
        }));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("40_filebase"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("repository root was not found from the test directory");
    }

    private static ActiveSkillServices services(
            SkillTargetingService targeting,
            SkillCombatService combat,
            SkillEffectService effects,
            SkillTaskService tasks
    ) {
        return new ActiveSkillServices(
                targeting,
                combat,
                effects,
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                tasks
        );
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                MageFrostBlizzardExecutor.ID,
                MageFrostBlizzardExecutor.ID,
                "フロストブリザード",
                null,
                "DIAMOND_NAUTILUS_ARMOR",
                List.of(),
                400L,
                40.0D,
                20L,
                1,
                null,
                Map.of(),
                List.of("active", "magic"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                40.0D
        );
    }

    private static DisplayFixture displayFixture() {
        World world = mock(World.class);
        List<BlockDisplay> displays = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            BlockDisplay display = mock(BlockDisplay.class);
            when(display.isValid()).thenReturn(true);
            displays.add(display);
        }
        AtomicInteger nextDisplay = new AtomicInteger();
        when(world.spawn(
                any(Location.class), eq(BlockDisplay.class),
                org.mockito.ArgumentMatchers.<Consumer<? super BlockDisplay>>any()
        )).thenAnswer(invocation -> {
            BlockDisplay display = displays.get(nextDisplay.getAndIncrement());
            Consumer<BlockDisplay> initializer = invocation.getArgument(2);
            initializer.accept(display);
            return display;
        });
        return new DisplayFixture(world, displays);
    }

    private record DisplayFixture(World world, List<BlockDisplay> displays) {
    }
}
