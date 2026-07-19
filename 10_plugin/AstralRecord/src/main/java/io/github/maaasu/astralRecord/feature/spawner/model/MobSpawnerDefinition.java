package io.github.maaasu.astralRecord.feature.spawner.model;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * filebase から読み込む Mob スポナーの静的定義です。
 *
 * @param id                   スポナー ID
 * @param region               このスポナー範囲を表す地域名。未設定時は地域判定に使用しません
 * @param radiusMeters         スポーン地点からの有効半径
 * @param spawnMobs            スポーン対象 Mob の重み付き一覧
 * @param timeWindows          スポーン可能時間帯
 * @param itemMaterial         管理者用スポナーアイテムの見た目 Material
 * @param spawnIntervalTicks   スポーン判定間隔
 * @param maxAlivePerSpawner   このスポナー由来で同時に存在できる最大数
 * @param maxNearbyMobs        周辺全体の Mob 上限。他スポナーとの湧き過ぎ抑制に使います
 * @param spawnPerPlayer       プレイヤー 1 人あたりの目標スポーン数
 */
public record MobSpawnerDefinition(
        @NotNull String id,
        @Nullable String region,
        double radiusMeters,
        @NotNull List<MobSpawnerEntry> spawnMobs,
        @NotNull List<MobSpawnerTimeWindow> timeWindows,
        @NotNull Material itemMaterial,
        long spawnIntervalTicks,
        int maxAlivePerSpawner,
        int maxNearbyMobs,
        int spawnPerPlayer
) {

    private static final int MAX_PLAYER_SCALE = 6;

    public MobSpawnerDefinition {
        region = region == null || region.isBlank() ? null : region.trim();
        radiusMeters = Math.max(1.0D, radiusMeters);
        spawnMobs = spawnMobs == null ? List.of() : List.copyOf(spawnMobs);
        timeWindows = timeWindows == null || timeWindows.isEmpty()
                ? List.of(MobSpawnerTimeWindow.allDay())
                : List.copyOf(timeWindows);
        if (itemMaterial == null || !itemMaterial.isBlock()) {
            itemMaterial = Material.SPAWNER;
        }
        spawnIntervalTicks = Math.max(20L, spawnIntervalTicks);
        maxAlivePerSpawner = Math.max(1, maxAlivePerSpawner);
        maxNearbyMobs = Math.max(maxAlivePerSpawner, maxNearbyMobs);
        spawnPerPlayer = Math.max(1, spawnPerPlayer);
    }

    /**
     * 近くにいるプレイヤー数から目標スポーン数を算出します。
     *
     * @param nearbyPlayers 近くにいるプレイヤー数
     * @return このスポナーが維持したい Mob 数
     */
    public int desiredAliveCount(int nearbyPlayers) {
        int scaledPlayers = Math.max(0, Math.min(MAX_PLAYER_SCALE, nearbyPlayers));
        return Math.min(maxAlivePerSpawner, scaledPlayers * spawnPerPlayer);
    }

    /**
     * ワールド時刻がスポーン可能時間帯に含まれるか判定します。
     *
     * @param time Minecraft ワールド時刻
     * @return スポーン可能なら true
     */
    public boolean canSpawnAt(long time) {
        return timeWindows.stream().anyMatch(window -> window.contains(time));
    }
}
