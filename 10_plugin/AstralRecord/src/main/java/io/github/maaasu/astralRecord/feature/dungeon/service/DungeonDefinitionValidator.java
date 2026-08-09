package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ダンジョン定義の構造と、World/Mob マスタへの参照を公開前に検証します。 */
public final class DungeonDefinitionValidator {
    private static final int MAX_AREA_BLOCKS = 65_536;
    private static final int MAX_ROOMS = 64;

    /**
     * 定義一覧を一括検証します。
     *
     * @param definitions ダンジョン定義
     * @param mobsById 同時に公開する Mob マスタ
     * @param worldsById 同時に公開する World マスタ
     */
    public void validateAll(
            @NotNull List<DungeonDefinition> definitions,
            @NotNull Map<String, MobTemplate> mobsById,
            @NotNull Map<String, WorldMasterData> worldsById
    ) {
        Set<String> ids = new HashSet<>();
        for (DungeonDefinition definition : definitions) {
            if (!ids.add(definition.id())) {
                fail(definition, "duplicate id");
            }
            validate(definition, mobsById, worldsById);
        }
    }

    private void validate(
            @NotNull DungeonDefinition definition,
            @NotNull Map<String, MobTemplate> mobsById,
            @NotNull Map<String, WorldMasterData> worldsById
    ) {
        if (definition.schemaVersion() != 1 || definition.id().isBlank() || definition.displayName().isBlank()) {
            fail(definition, "schemaVersion, id and displayName are required");
        }
        validateRange(definition, "party", definition.partySize(), 1, 6);

        WorldMasterData world = worldsById.get(definition.worldId());
        if (world == null) {
            fail(definition, "worldRef does not exist: " + definition.worldId());
        }
        if (world.worldType() != WorldType.DUNGEON || !world.instanceEnabled()) {
            fail(definition, "worldRef must be DUNGEON and instanceEnabled=true: " + definition.worldId());
        }
        if (world.instanceRootPath().isBlank()) {
            fail(definition, "worldRef.instanceRootPath is required");
        }
        if (world.autoLoad()) {
            fail(definition, "procedural dungeon worldRef must use autoLoad=false");
        }
        if (world.allowBlockBreak() || world.allowBlockPlace() || world.allowMobSpawn()) {
            fail(definition, "dungeon worldRef must disable block break/place and natural mob spawn");
        }
        if (world.maxPlayers() < definition.partySize().max()) {
            fail(definition, "worldRef.maxPlayers is smaller than party.max");
        }

        DungeonDefinition.Generation generation = definition.generation();
        if (generation.areaWidth() < 32 || generation.areaDepth() < 32
                || generation.areaWidth() > 256 || generation.areaDepth() > 256
                || generation.areaWidth() * generation.areaDepth() > MAX_AREA_BLOCKS) {
            fail(definition, "generation.area must be 32..256 per side and at most " + MAX_AREA_BLOCKS + " blocks");
        }
        validateRange(definition, "generation.roomCount", generation.roomCount(), 3, MAX_ROOMS);
        validateRange(definition, "generation.roomSize", generation.roomSize(), 7, 64);
        if (generation.roomHeight() < 5 || generation.roomHeight() > 32) {
            fail(definition, "generation.roomHeight must be 5..32");
        }
        if (generation.baseY() < -60 || generation.baseY() + generation.roomHeight() > 316) {
            fail(definition, "generation.baseY and roomHeight must fit inside -60..316");
        }
        if (generation.corridorWidth() < 1 || generation.corridorWidth() > 7
                || generation.corridorWidth() % 2 == 0) {
            fail(definition, "generation.corridorWidth must be an odd value in 1..7");
        }
        if (generation.corridorHeight() < 2
                || generation.corridorHeight() > generation.roomHeight() - 2) {
            fail(definition, "generation.corridorHeight must be 2..roomHeight-2");
        }
        if (generation.splitRatioMin() < 0.25D
                || generation.splitRatioMax() > 0.5D
                || generation.splitRatioMin() > generation.splitRatioMax()) {
            fail(definition, "generation.splitRatio must be ordered inside 0.25..0.50");
        }
        validatePositiveWeights(definition, "generation.roomShapes", generation.roomShapes());
        int minimumPartition = generation.roomSize().min() + 4;
        int capacity = Math.max(1, generation.areaWidth() / minimumPartition)
                * Math.max(1, generation.areaDepth() / minimumPartition);
        if (capacity < generation.roomCount().max()) {
            fail(definition, "generation.area cannot fit roomCount.max with roomSize.min");
        }

        validatePositiveWeights(definition, "theme.floor", definition.theme().floor());
        validatePositiveWeights(definition, "theme.wall", definition.theme().wall());
        validatePositiveWeights(definition, "theme.ceiling", definition.theme().ceiling());
        validatePositiveWeights(definition, "theme.corridor", definition.theme().corridor());
        if (definition.theme().pillar().chance() < 0.0D
                || definition.theme().pillar().chance() > 1.0D) {
            fail(definition, "theme.pillar.chance must be 0.0..1.0");
        }

        DungeonDefinition.Encounter encounter = definition.encounter();
        validateRange(definition, "encounter.mobsPerRoom", encounter.mobsPerRoom(), 1, 16);
        if (encounter.firstCombatRoomMaxMobLevel() < 1) {
            fail(definition, "encounter.firstCombatRoomMaxMobLevel must be positive");
        }
        validatePositiveWeights(definition, "encounter.normalMobPool", encounter.normalMobPool());
        boolean firstRoomCandidate = false;
        for (DungeonDefinition.WeightedMob entry : encounter.normalMobPool()) {
            MobTemplate mob = mobsById.get(entry.mobId());
            if (mob == null) {
                fail(definition, "normal mob does not exist: " + entry.mobId());
            }
            if (mob.category() != MobCategory.ENEMY) {
                fail(definition, "normal mob must use ENEMY category: " + entry.mobId());
            }
            if (mob.level() <= encounter.firstCombatRoomMaxMobLevel()) {
                firstRoomCandidate = true;
            }
        }
        if (!firstRoomCandidate) {
            fail(definition, "normalMobPool has no candidate for firstCombatRoomMaxMobLevel");
        }
        MobTemplate boss = mobsById.get(encounter.bossMobId());
        if (boss == null || boss.category() != MobCategory.BOSS) {
            fail(definition, "bossMobId must reference a BOSS mob: " + encounter.bossMobId());
        }
    }

    private void validateRange(
            @NotNull DungeonDefinition definition,
            @NotNull String path,
            @NotNull DungeonDefinition.IntRange range,
            int minimum,
            int maximum
    ) {
        if (range.min() < minimum || range.max() > maximum || range.min() > range.max()) {
            fail(definition, path + " must be ordered inside " + minimum + ".." + maximum);
        }
    }

    private void validatePositiveWeights(
            @NotNull DungeonDefinition definition,
            @NotNull String path,
            @NotNull List<?> entries
    ) {
        if (entries.isEmpty()) {
            fail(definition, path + " must not be empty");
        }
        for (Object entry : entries) {
            int weight = switch (entry) {
                case DungeonDefinition.WeightedShape value -> value.weight();
                case DungeonDefinition.WeightedMaterial value -> value.weight();
                case DungeonDefinition.WeightedMob value -> value.weight();
                default -> 0;
            };
            if (weight <= 0) {
                fail(definition, path + " weights must be positive");
            }
        }
    }

    private void fail(@NotNull DungeonDefinition definition, @NotNull String reason) {
        throw new IllegalArgumentException("Dungeon '" + definition.id() + "': " + reason);
    }
}
