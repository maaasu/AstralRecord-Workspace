package io.github.maaasu.astralRecord.feature.dungeon.model;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 1 種類の自動生成ダンジョンを表す filebase マスタです。
 *
 * <p>BSP の分割木や座標は実行時に seed から導出し、マスタにはゲームデザイン上の
 * 調整値だけを保持します。</p>
 */
public record DungeonDefinition(
        int schemaVersion,
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String worldId,
        @NotNull IntRange partySize,
        @NotNull Generation generation,
        @NotNull Theme theme,
        @NotNull Encounter encounter
) {
    public DungeonDefinition {
        id = id.trim();
        displayName = displayName.trim();
        worldId = worldId.trim();
    }

    /** 最小値と最大値を両端を含めて表す範囲です。 */
    public record IntRange(int min, int max) {
    }

    /** BSP と部屋・通路の寸法設定です。 */
    public record Generation(
            int areaWidth,
            int areaDepth,
            int baseY,
            @NotNull IntRange roomCount,
            @NotNull IntRange roomSize,
            int roomHeight,
            int corridorWidth,
            int corridorHeight,
            double splitRatioMin,
            double splitRatioMax,
            @NotNull List<WeightedShape> roomShapes
    ) {
        public Generation {
            roomShapes = List.copyOf(roomShapes);
        }
    }

    /** 部屋形状と相対抽選重みです。 */
    public record WeightedShape(@NotNull DungeonRoomShape shape, int weight) {
    }

    /** ブロックテーマと任意の中央柱設定です。 */
    public record Theme(
            @NotNull List<WeightedMaterial> floor,
            @NotNull List<WeightedMaterial> wall,
            @NotNull List<WeightedMaterial> ceiling,
            @NotNull List<WeightedMaterial> corridor,
            @NotNull Material gateMaterial,
            @NotNull Pillar pillar
    ) {
        public Theme {
            floor = List.copyOf(floor);
            wall = List.copyOf(wall);
            ceiling = List.copyOf(ceiling);
            corridor = List.copyOf(corridor);
        }
    }

    /** Material と相対抽選重みです。合計値を 100 に揃える必要はありません。 */
    public record WeightedMaterial(@NotNull Material material, int weight) {
    }

    /** 部屋中央へ生成する装飾柱です。 */
    public record Pillar(
            boolean enabled,
            double chance,
            @NotNull Material material,
            @NotNull Material stairMaterial
    ) {
    }

    /** 通常部屋とボス部屋の戦闘設定です。 */
    public record Encounter(
            @NotNull List<WeightedMob> normalMobPool,
            @NotNull IntRange mobsPerRoom,
            int firstCombatRoomMaxMobLevel,
            @NotNull String bossMobId
    ) {
        public Encounter {
            normalMobPool = List.copyOf(normalMobPool);
            bossMobId = bossMobId.trim();
        }
    }

    /** Mob ID と相対抽選重みです。 */
    public record WeightedMob(@NotNull String mobId, int weight) {
        public WeightedMob {
            mobId = mobId.trim();
        }
    }
}
