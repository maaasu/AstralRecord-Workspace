package io.github.maaasu.astralRecord.feature.dungeon;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;

/** Dungeon の純粋計算テストで共有する有効な最小定義です。 */
public final class DungeonTestFixtures {
    private DungeonTestFixtures() {
    }

    public static DungeonDefinition definition() {
        return new DungeonDefinition(
                1,
                "test_dungeon",
                "Test Dungeon",
                new DungeonDefinition.Entry("test_entry_world", 1.5D, 64.0D, 2.5D, 0.0F, 0.0F, 2.0D),
                new DungeonDefinition.IntRange(1, 4),
                new DungeonDefinition.Generation(
                        128,
                        128,
                        64,
                        new DungeonDefinition.IntRange(7, 11),
                        new DungeonDefinition.IntRange(11, 23),
                        8,
                        3,
                        4,
                        0.35D,
                        0.50D,
                        List.of(
                                new DungeonDefinition.WeightedShape(DungeonRoomShape.RECTANGLE, 70),
                                new DungeonDefinition.WeightedShape(DungeonRoomShape.CYLINDER, 30)
                        )
                ),
                new DungeonDefinition.Theme(
                        List.of(new DungeonDefinition.WeightedMaterial(Material.STONE_BRICKS, 1)),
                        List.of(new DungeonDefinition.WeightedMaterial(Material.STONE_BRICKS, 1)),
                        List.of(new DungeonDefinition.WeightedMaterial(Material.STONE_BRICKS, 1)),
                        List.of(new DungeonDefinition.WeightedMaterial(Material.COBBLESTONE, 1)),
                        Material.IRON_BARS,
                        new DungeonDefinition.Pillar(
                                true,
                                0.35D,
                                Material.CHISELED_STONE_BRICKS,
                                Material.STONE_BRICK_STAIRS
                        )
                ),
                new DungeonDefinition.Encounter(
                        List.of(
                                new DungeonDefinition.WeightedMob("weak", 70),
                                new DungeonDefinition.WeightedMob("strong", 30)
                        ),
                        new DungeonDefinition.IntRange(2, 5),
                        10,
                        "boss"
                )
        );
    }

    public static MobTemplate mob(String id, int level, MobCategory category) {
        return new MobTemplate(
                1,
                id,
                category,
                id,
                null,
                level,
                EntityType.ZOMBIE,
                false,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        );
    }
}
