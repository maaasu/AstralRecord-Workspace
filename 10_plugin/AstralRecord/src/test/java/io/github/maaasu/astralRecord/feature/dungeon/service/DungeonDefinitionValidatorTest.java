package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomType;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonDefinitionValidatorTest {
    private final DungeonDefinitionValidator validator = new DungeonDefinitionValidator();

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md
     * 章・見出し: # 32_0-概要 > ## 4. 主要境界と不変条件
     * 検証契約: 最初の戦闘部屋レベル上限内のENEMY候補とBOSS参照を持つ定義だけを公開可能とする。
     */
    @Test
    void acceptsDefinitionWithEligibleFirstRoomEnemyAndBossReference() {
        DungeonDefinition definition = DungeonTestFixtures.definition();
        Map<String, MobTemplate> mobs = Map.of(
                "weak", DungeonTestFixtures.mob("weak", 5, MobCategory.ENEMY),
                "strong", DungeonTestFixtures.mob("strong", 20, MobCategory.ENEMY),
                "boss", DungeonTestFixtures.mob("boss", 30, MobCategory.BOSS)
        );

        assertDoesNotThrow(() -> validator.validateAll(
                List.of(definition), mobs, Map.of(definition.entry().worldId(), world(definition.entry().worldId()))));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 1. DungeonDefinition
     * 検証契約: プレイヤー向け推奨レベルは1以上の整数だけを公開可能とする。
     */
    @Test
    void rejectsNonPositiveRecommendedLevel() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition invalid = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), -1,
                source.entry(), source.partySize(), source.challenge(), source.generation(),
                source.theme(), source.encounter(), source.clearRewards());

        assertThrows(IllegalArgumentException.class, () -> validator.validateAll(
                List.of(invalid), Map.of(), Map.of()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md
     * 章・見出し: # 32_0-概要 > ## 4. 主要境界と不変条件
     * 検証契約: normalMobPoolに最初の戦闘部屋レベル上限内の候補が一体もなければ定義公開を拒否する。
     */
    @Test
    void rejectsDefinitionWithoutAnyEligibleFirstRoomEnemy() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition invalid = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                source.generation(), source.theme(),
                new DungeonDefinition.Encounter(
                        List.of(new DungeonDefinition.WeightedMob("strong", 1)),
                        source.encounter().mobsPerRoom(),
                        source.encounter().firstCombatRoomMaxMobLevel(),
                        source.encounter().bossMobId()
                )
        );
        Map<String, MobTemplate> mobs = Map.of(
                "strong", DungeonTestFixtures.mob("strong", 99, MobCategory.ENEMY),
                "boss", DungeonTestFixtures.mob("boss", 30, MobCategory.BOSS)
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validateAll(
                List.of(invalid), mobs, Map.of(invalid.entry().worldId(), world(invalid.entry().worldId()))));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md
     * 章・見出し: # 32_0-概要 > ## 4. 主要境界と不変条件
     * 検証契約: 挑戦受付半径は0.5から16.0の範囲だけを許可する。
     */
    @Test
    void rejectsEntryRadiusOutsideSupportedRange() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition invalid = new DungeonDefinition(
                source.schemaVersion(),
                source.id(),
                source.displayName(),
                source.recommendedLevel(),
                new DungeonDefinition.Entry(
                        source.entry().worldId(),
                        source.entry().x(),
                        source.entry().y(),
                        source.entry().z(),
                        source.entry().yaw(),
                        source.entry().pitch(),
                        0.25D
                ),
                source.partySize(),
                source.generation(),
                source.theme(),
                source.encounter()
        );
        Map<String, MobTemplate> mobs = Map.of(
                "weak", DungeonTestFixtures.mob("weak", 5, MobCategory.ENEMY),
                "strong", DungeonTestFixtures.mob("strong", 20, MobCategory.ENEMY),
                "boss", DungeonTestFixtures.mob("boss", 30, MobCategory.BOSS)
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validateAll(
                List.of(invalid), mobs, Map.of(invalid.entry().worldId(), world(invalid.entry().worldId()))));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md
     * 章・見出し: # 32_0-概要 > ## 4. 主要境界と不変条件
     * 検証契約: 明示した負の死亡許容回数と1秒未満の復帰待機時間を補正せず公開拒否する。
     */
    @Test
    void rejectsInvalidChallengeBoundariesWithoutClamping() {
        DungeonDefinition source = DungeonTestFixtures.definition();

        assertThrows(IllegalArgumentException.class, () -> validator.validateAll(
                List.of(withChallenge(source, new DungeonDefinition.Challenge(-1, 5L))), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAll(
                List.of(withChallenge(source, new DungeonDefinition.Challenge(0, 0L))), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAll(
                List.of(withChallenge(source, new DungeonDefinition.Challenge(0, 5L, 29L))), Map.of(), Map.of()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md
     * 章・見出し: # 32_0-概要 > ## 4. 主要境界と不変条件
     * 検証契約: clear rewardのrate範囲外・非有限値と正整数昇順rangeでないamountを公開拒否する。
     */
    @Test
    void rejectsInvalidClearRewardRateAndAmountBoundaries() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        for (MobDropItem invalid : List.of(
                new MobDropItem("reward", -0.01D, "1", false, false),
                new MobDropItem("reward", 100.01D, "1", false, false),
                new MobDropItem("reward", Double.NaN, "1", false, false),
                new MobDropItem("reward", 50.0D, "0", false, false),
                new MobDropItem("reward", 50.0D, "3~1", false, false),
                new MobDropItem("reward", 50.0D, "invalid", false, false)
        )) {
            assertThrows(IllegalArgumentException.class, () -> validator.validateAll(
                    List.of(withRewards(source, invalid)), Map.of(), Map.of()));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md
     * 章・見出し: # 32_0-概要 > ## 4. 主要境界と不変条件
     * 検証契約: roomTypesおよび部屋タイプ装飾paletteは空でなく、すべての相対weightが正でなければ公開しない。
     */
    @Test
    void rejectsNonPositiveRoomTypeAndDecorationWeights() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition.Generation generation = source.generation();
        DungeonDefinition invalidRoomType = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                source.challenge(),
                new DungeonDefinition.Generation(
                        generation.areaWidth(), generation.areaDepth(), generation.baseY(),
                        generation.roomCount(), generation.roomSize(), generation.roomHeight(),
                        generation.corridorWidth(), generation.corridorHeight(),
                        generation.splitRatioMin(), generation.splitRatioMax(), generation.roomShapes(),
                        List.of(new DungeonDefinition.WeightedRoomType(DungeonRoomType.STANDARD, 0))
                ),
                source.theme(), source.encounter(), source.clearRewards()
        );
        DungeonDefinition.Theme theme = source.theme();
        DungeonDefinition invalidDecoration = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                source.challenge(), source.generation(),
                new DungeonDefinition.Theme(
                        theme.floor(), theme.wall(), theme.ceiling(), theme.corridor(),
                        theme.gateMaterial(), theme.pillar(), theme.lightMaterial(),
                        new DungeonDefinition.Decorations(
                                theme.decorations().supportMaterial(),
                                theme.decorations().beamMaterial(),
                                List.of(new DungeonDefinition.WeightedMaterial(org.bukkit.Material.COBBLESTONE, 0)),
                                theme.decorations().accent()
                        )
                ),
                source.encounter(), source.clearRewards()
        );
        Map<String, MobTemplate> mobs = Map.of(
                "weak", DungeonTestFixtures.mob("weak", 5, MobCategory.ENEMY),
                "strong", DungeonTestFixtures.mob("strong", 20, MobCategory.ENEMY),
                "boss", DungeonTestFixtures.mob("boss", 30, MobCategory.BOSS)
        );
        Map<String, WorldMasterData> worlds = Map.of(
                source.entry().worldId(), world(source.entry().worldId()));

        IllegalArgumentException roomFailure = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAll(List.of(invalidRoomType), mobs, worlds));
        IllegalArgumentException decorationFailure = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAll(List.of(invalidDecoration), mobs, worlds));

        assertTrue(roomFailure.getMessage().contains("generation.roomTypes"));
        assertTrue(decorationFailure.getMessage().contains("theme.decorations.rubble"));
    }

    private DungeonDefinition withChallenge(
            DungeonDefinition source,
            DungeonDefinition.Challenge challenge
    ) {
        return new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                challenge, source.generation(), source.theme(), source.encounter(), source.clearRewards());
    }

    private DungeonDefinition withRewards(DungeonDefinition source, MobDropItem item) {
        return new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                source.challenge(), source.generation(), source.theme(), source.encounter(),
                new MobDropConfig(0, null, List.of(item), null));
    }

    private WorldMasterData world(String id) {
        return new WorldMasterData(
                1,
                id,
                "Dungeon Instance",
                WorldType.OVERWORLD,
                "",
                "instances/dungeons",
                false,
                true,
                6,
                false,
                false,
                false,
                false,
                WorldSpawnLocation.defaultLocation(),
                "test",
                null,
                null,
                null
        );
    }
}
