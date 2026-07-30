package io.github.maaasu.astralRecord.feature.trainingdummy.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** プラグイン設定に保存するカカシ配置と検証ステータスです。 */
public record TrainingDummyDefinition(
        @NotNull String id,
        @NotNull String worldName,
        double x,
        double y,
        double z,
        float yaw,
        double maxHealth,
        double defense,
        double magicDefense,
        boolean shieldEnabled,
        double shieldMax,
        long recoveryIntervalTicks
) {
    /** カカシへ固定適用する最大 HP です。 */
    public static final double FIXED_MAX_HEALTH = Integer.MAX_VALUE;

    public TrainingDummyDefinition {
        id = id.trim();
        worldName = worldName.trim();
        maxHealth = FIXED_MAX_HEALTH;
        defense = Math.max(0.0D, defense);
        magicDefense = Math.max(0.0D, magicDefense);
        shieldMax = Math.max(0.0D, shieldMax);
        recoveryIntervalTicks = Math.max(20L, recoveryIntervalTicks);
    }

    /** 設置座標を取得します。ワールド未ロード時は null です。 */
    public @Nullable Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, 0.0F);
    }

    /** 指定値だけを更新したコピーを返します。 */
    public @NotNull TrainingDummyDefinition withStats(
            double nextMaxHealth,
            double nextDefense,
            double nextMagicDefense,
            boolean nextShieldEnabled,
            double nextShieldMax
    ) {
        return new TrainingDummyDefinition(
                id, worldName, x, y, z, yaw, nextMaxHealth, nextDefense, nextMagicDefense,
                nextShieldEnabled, nextShieldMax, recoveryIntervalTicks
        );
    }
}
