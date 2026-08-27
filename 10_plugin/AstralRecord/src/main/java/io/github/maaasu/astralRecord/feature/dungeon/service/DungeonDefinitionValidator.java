package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
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
        if (definition.recommendedLevel() < 1) {
            fail(definition, "recommendedLevel must be positive");
        }
        validateRange(definition, "party", definition.partySize(), 1, 6);
        if (definition.challenge().deathLimit() < 0) {
            fail(definition, "challenge.deathLimit must be zero or greater");
        }
        if (definition.challenge().reviveDelaySeconds() < 1L) {
            fail(definition, "challenge.reviveDelaySeconds must be positive");
        }
        definition.clearRewards().items().forEach(item -> {
            if (item.itemId() == null || item.itemId().isBlank()
                    || !Double.isFinite(item.rate()) || item.rate() < 0.0D || item.rate() > 100.0D
                    || !isValidAmount(item.amount())) {
                fail(definition, "clearRewards.items contains an invalid entry");
            }
        });

        DungeonDefinition.Entry entry = definition.entry();
        WorldMasterData world = worldsById.get(entry.worldId());
        if (world == null) {
            fail(definition, "entry.worldRef does not exist: " + entry.worldId());
        }
        if (!Double.isFinite(entry.x()) || !Double.isFinite(entry.y()) || !Double.isFinite(entry.z())
                || !Double.isFinite(entry.radius()) || entry.radius() < 0.5D || entry.radius() > 16.0D) {
            fail(definition, "entry coordinates must be finite and radius must be 0.5..16.0");
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
        validatePositiveWeights(definition, "generation.roomTypes", generation.roomTypes());
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
        validatePositiveWeights(definition, "theme.decorations.rubble", definition.theme().decorations().rubble());
        validatePositiveWeights(definition, "theme.decorations.accent", definition.theme().decorations().accent());
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
        for (DungeonDefinition.WeightedMob mobEntry : encounter.normalMobPool()) {
            MobTemplate baseMob = mobsById.get(mobEntry.mobId());
            if (baseMob == null) {
                fail(definition, "normal mob does not exist: " + mobEntry.mobId());
            }
            MobTemplate mob = baseMob.resolveLevel(mobEntry.level());
            if (mob.category() != MobCategory.ENEMY) {
                fail(definition, "normal mob must use ENEMY category: " + mobEntry.mobId());
            }
            if (mob.level() <= encounter.firstCombatRoomMaxMobLevel()) {
                firstRoomCandidate = true;
            }
        }
        if (!firstRoomCandidate) {
            fail(definition, "normalMobPool has no candidate for firstCombatRoomMaxMobLevel");
        }
        MobTemplate bossBase = mobsById.get(encounter.bossMobId());
        MobTemplate boss = bossBase == null ? null : bossBase.resolveLevel(encounter.bossMobLevel());
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
                case DungeonDefinition.WeightedRoomType value -> value.weight();
                case DungeonDefinition.WeightedMaterial value -> value.weight();
                case DungeonDefinition.WeightedMob value -> value.weight();
                default -> 0;
            };
            if (weight <= 0) {
                fail(definition, path + " weights must be positive");
            }
        }
    }

    private boolean isValidAmount(String amount) {
        if (amount == null || amount.isBlank()) return false;
        String[] parts = amount.trim().split("~", -1);
        if (parts.length < 1 || parts.length > 2) return false;
        try {
            int minimum = Integer.parseInt(parts[0]);
            int maximum = parts.length == 1 ? minimum : Integer.parseInt(parts[1]);
            return minimum >= 1 && maximum >= minimum;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private void fail(@NotNull DungeonDefinition definition, @NotNull String reason) {
        throw new IllegalArgumentException("Dungeon '" + definition.id() + "': " + reason);
    }
}
