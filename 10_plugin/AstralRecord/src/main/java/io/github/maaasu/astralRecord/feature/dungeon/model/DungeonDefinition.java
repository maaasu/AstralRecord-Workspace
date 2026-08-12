package io.github.maaasu.astralRecord.feature.dungeon.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
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
        @NotNull Entry entry,
        @NotNull IntRange partySize,
        @NotNull Challenge challenge,
        @NotNull Generation generation,
        @NotNull Theme theme,
        @NotNull Encounter encounter,
        @NotNull MobDropConfig clearRewards
) {
    public DungeonDefinition {
        id = id.trim();
        displayName = displayName.trim();
    }

    /**
     * 死亡制限・クリア報酬追加前の呼び出し元向け互換コンストラクタです。
     *
     * @param schemaVersion スキーマ版
     * @param id ダンジョン ID
     * @param displayName 表示名
     * @param entry 受付地点
     * @param partySize 参加人数範囲
     * @param generation 生成設定
     * @param theme 外観設定
     * @param encounter 戦闘設定
     */
    public DungeonDefinition(
            int schemaVersion,
            @NotNull String id,
            @NotNull String displayName,
            @NotNull Entry entry,
            @NotNull IntRange partySize,
            @NotNull Generation generation,
            @NotNull Theme theme,
            @NotNull Encounter encounter
    ) {
        this(
                schemaVersion,
                id,
                displayName,
                entry,
                partySize,
                new Challenge(5, 5L),
                generation,
                theme,
                encounter,
                new MobDropConfig(0, null, List.of(), null)
        );
    }

    /** 挑戦を受け付ける通常ワールド上の地点です。 */
    public record Entry(
            @NotNull String worldId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            double radius
    ) {
        public Entry {
            worldId = worldId.trim();
        }
    }

    /** 最小値と最大値を両端を含めて表す範囲です。 */
    public record IntRange(int min, int max) {
    }

    /** パーティー共有の死亡許容回数と死亡後の復帰待機時間です。 */
    public record Challenge(int deathLimit, long reviveDelaySeconds) {
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
            @NotNull List<WeightedShape> roomShapes,
            @NotNull List<WeightedRoomType> roomTypes
    ) {
        public Generation {
            roomShapes = List.copyOf(roomShapes);
            roomTypes = List.copyOf(roomTypes);
        }

        /** 部屋タイプ導入前の呼び出し元向け互換コンストラクタです。 */
        public Generation(
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
            this(areaWidth, areaDepth, baseY, roomCount, roomSize, roomHeight,
                    corridorWidth, corridorHeight, splitRatioMin, splitRatioMax, roomShapes,
                    List.of(new WeightedRoomType(DungeonRoomType.STANDARD, 1)));
        }
    }

    /** 部屋形状と相対抽選重みです。 */
    public record WeightedShape(@NotNull DungeonRoomShape shape, int weight) {
    }

    /** 部屋タイプと相対抽選重みです。 */
    public record WeightedRoomType(@NotNull DungeonRoomType type, int weight) {
    }

    /** ブロックテーマと任意の中央柱設定です。 */
    public record Theme(
            @NotNull List<WeightedMaterial> floor,
            @NotNull List<WeightedMaterial> wall,
            @NotNull List<WeightedMaterial> ceiling,
            @NotNull List<WeightedMaterial> corridor,
            @NotNull Material gateMaterial,
            @NotNull Pillar pillar,
            @NotNull Material lightMaterial,
            @NotNull Decorations decorations
    ) {
        public Theme {
            floor = List.copyOf(floor);
            wall = List.copyOf(wall);
            ceiling = List.copyOf(ceiling);
            corridor = List.copyOf(corridor);
        }

        /** 照明・部屋タイプ装飾導入前の呼び出し元向け互換コンストラクタです。 */
        public Theme(
                @NotNull List<WeightedMaterial> floor,
                @NotNull List<WeightedMaterial> wall,
                @NotNull List<WeightedMaterial> ceiling,
                @NotNull List<WeightedMaterial> corridor,
                @NotNull Material gateMaterial,
                @NotNull Pillar pillar
        ) {
            this(
                    floor,
                    wall,
                    ceiling,
                    corridor,
                    gateMaterial,
                    pillar,
                    Material.TORCH,
                    new Decorations(
                            Material.OAK_LOG,
                            Material.OAK_PLANKS,
                            List.of(new WeightedMaterial(Material.COBBLESTONE, 1)),
                            List.of(new WeightedMaterial(Material.COAL_ORE, 1))
                    )
            );
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

    /** 部屋タイプ別の汎用装飾素材です。 */
    public record Decorations(
            @NotNull Material supportMaterial,
            @NotNull Material beamMaterial,
            @NotNull List<WeightedMaterial> rubble,
            @NotNull List<WeightedMaterial> accent
    ) {
        public Decorations {
            rubble = List.copyOf(rubble);
            accent = List.copyOf(accent);
        }
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
