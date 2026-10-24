package io.github.maaasu.astralRecord.shared.effect;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * パーティクル表示の共通サービスです。
 * 統合版では同じ表示座標を小型ダストに変換し、一括表示は最大96地点、単発散布は最大4粒に抑えます。
 */
public class ParticleDisplayService {

    private static final double PLUGIN_PARTICLE_DENSITY_SCALE = 1.0D;
    private static final double DEFAULT_VIEWER_DISTANCE_SQUARED = 64.0D * 64.0D;
    private static final int BEDROCK_MAX_POINTS = 96;
    private static final int BEDROCK_MAX_BURST_COUNT = 4;

    private final PlayerSettingService playerSettingService;

    /** 設定サービスを持たず、閲覧者へ標準密度を使う互換サービスを生成します。 */
    public ParticleDisplayService() {
        this(null);
    }

    /**
     * 閲覧者の設定を使う粒子サービスを生成します。
     * @param playerSettingService 設定サービス。nullは標準密度を使います
     */
    public ParticleDisplayService(@Nullable PlayerSettingService playerSettingService) {
        this.playerSettingService = playerSettingService;
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param astPlayer 互換呼び出し元。表示密度は閲覧者ごとに解決します
     * @param world 表示先world。locationと同じworldを指定します
     * @param location 表示する一地点
     * @param definition 種類・基準個数・拡散・追加データの共通定義
     */
    public void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        spawnForNearbyViewers(location, definition);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param <T> 粒子追加データの型
     * @param astPlayer 互換呼び出し元。表示密度は閲覧者ごとに解決します
     * @param world 表示先world。locationと同じworldを指定します
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     * @param data 種類に合う追加データ。不要な場合null
     */
    public <T> void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForNearbyViewers(location, particle, baseCount, offsetX, offsetY, offsetZ, extra, data);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param astPlayer 互換呼び出し元。表示密度は閲覧者ごとに解決します
     * @param world 表示先world。locationと同じworldを指定します
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     */
    public void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnWorld(astPlayer, world, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param world 表示先world。locationと同じworldを指定します
     * @param location 表示する一地点
     * @param definition 種類・基準個数・拡散・追加データの共通定義
     * @param playerDensityScale 表示密度。標準1、最大2、0以下は非表示
     */
    public void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition,
        double playerDensityScale
    ) {
        if (definition.hideForBedrock()) {
            for (Player viewer : world.getPlayers()) {
                spawnForViewer(viewer, location, definition, playerDensityScale);
            }
            return;
        }
        spawnWorld(
            world,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            playerDensityScale,
            definition.data()
        );
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param world 表示先world。locationと同じworldを指定します
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     * @param playerDensityScale 表示密度。標準1、最大2、0以下は非表示
     */
    public void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale
    ) {
        spawnWorld(world, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, playerDensityScale, null);
    }

    /**
     * 64m以内の閲覧者へ接続種別に合った単発演出を表示します。
     * 固定点は一粒、散布は個数または表示頻度へ指定密度を適用します。
     * @param <T> 粒子追加データの型
     * @param world 表示先world。locationと同じworldを指定します
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     * @param playerDensityScale 表示密度。標準1、最大2、0以下は非表示
     * @param data 種類に合う追加データ。不要な場合null
     */
    public <T> void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale,
        @Nullable T data
    ) {
        for (Player viewer : world.getNearbyPlayers(location, Math.sqrt(DEFAULT_VIEWER_DISTANCE_SQUARED))) {
            spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra,
                playerDensityScale, data);
        }
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param definition 種類・基準個数・拡散・追加データの共通定義
     */
    public void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        if (shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
            return;
        }
        spawnForViewer(
            viewer,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data()
        );
    }

    /**
     * 指定した viewer へ複数地点の同じパーティクルを表示します。
     * <p>
     * 最大密度用の候補点から、閲覧者の密度に比例する地点数を均等に選びます。同座標は一度だけ描画します。
     *
     * @param viewer 表示対象プレイヤー
     * @param locations パーティクルを表示する地点
     * @param definition 表示する共通パーティクル定義
     */
    public void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition
    ) {
        if (locations.isEmpty() || shouldSkipForBedrock(viewer, definition.hideForBedrock())) return;
        Player bukkitViewer = viewer.getBukkit();
        List<Location> points = uniqueLocations(locations, bukkitViewer.getWorld());
        spawnBatch(bukkitViewer, points, definition, resolvePlayerDensityScale(viewer), false, false);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     */
    public void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param <T> 粒子追加データの型
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     * @param data 種類に合う追加データ。不要な場合null
     */
    public <T> void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForViewer(
            viewer.getBukkit(),
            location,
            particle,
            baseCount,
            offsetX,
            offsetY,
            offsetZ,
            extra,
            resolvePlayerDensityScale(viewer),
            data
        );
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param definition 種類・基準個数・拡散・追加データの共通定義
     */
    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        if (shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
            return;
        }
        spawnForViewer(
            viewer,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data()
        );
    }

    /**
     * Bukkit viewerへ最大密度の候補点を均等に間引いて表示し、密度と統合版判定を一度だけ解決します。
     * 距離と閲覧可否は呼び出し側で検証し、異なるworldの地点は送信しません。
     * @param viewer 表示対象プレイヤー。AstPlayer準備前でも使用できます
     * @param locations 同時に表示する地点
     * @param definition 共通パーティクル定義
     */
    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition
    ) {
        if (shouldSkipForBedrock(viewer, definition.hideForBedrock())) return;
        List<Location> points = uniqueLocations(locations, viewer.getWorld());
        spawnBatch(viewer, points, definition, resolvePlayerDensityScale(viewer), false, false);
    }

    /**
     * 解決済みの閲覧者一人へ、独立した発生源を保持して単発演出を表示します。
     * 距離と閲覧可否は呼び出し側で検証します。異なるworld、OFF、統合版の非表示指定を除外します。
     * @param viewer 呼び出し側で選択済みの表示対象
     * @param locations 各対象の独立した発生源。同座標は一回だけ表示します
     * @param definition 単発演出の共通定義
     */
    public void spawnBurstsForViewer(
        @NotNull Player viewer,
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition
    ) {
        double density = resolvePlayerDensityScale(viewer);
        if (density <= 0.0D || shouldSkipForBedrock(viewer, definition.hideForBedrock())) return;
        spawnBurstBatch(viewer, uniqueLocations(locations, viewer.getWorld()), definition, density, false, false);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param definition 種類・基準個数・拡散・追加データの共通定義
     * @param playerDensityScale 表示密度。標準1、最大2、0以下は非表示
     */
    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition,
        double playerDensityScale
    ) {
        if (shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
            return;
        }
        spawnForViewer(
            viewer,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            playerDensityScale,
            definition.data()
        );
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     */
    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param <T> 粒子追加データの型
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     * @param data 種類に合う追加データ。不要な場合null
     */
    public <T> void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, resolvePlayerDensityScale(viewer), data);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     * @param playerDensityScale 表示密度。標準1、最大2、0以下は非表示
     */
    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, playerDensityScale, null);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。
     * @param <T> 粒子追加データの型
     * @param viewer 表示を受け取るプレイヤー
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     * @param playerDensityScale 表示密度。標準1、最大2、0以下は非表示
     * @param data 種類に合う追加データ。不要な場合null
     */
    public <T> void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale,
        @Nullable T data
    ) {
        int count = resolveEmissionCount(baseCount, playerDensityScale, offsetX, offsetY, offsetZ, extra);
        spawnForViewerResolvedCount(viewer, location, particle, count, offsetX, offsetY, offsetZ, extra, data);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。閲覧者は地点から64m以内に限定します。
     * @param location 表示する一地点
     * @param definition 種類・基準個数・拡散・追加データの共通定義
     */
    public void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        spawnForNearbyViewers(
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data(),
            definition.hideForBedrock()
        );
    }

    /**
     * 同じ地点の複数パーティクルを、近傍viewerの走査を1回にまとめて表示します。
     *
     * @param location 表示地点
     * @param definitions 表示する共通パーティクル定義
     */
    public void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull Collection<SharedParticleDefinition> definitions
    ) {
        if (definitions.isEmpty()) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(location) > DEFAULT_VIEWER_DISTANCE_SQUARED) {
                continue;
            }
            double densityScale = resolvePlayerDensityScale(viewer);
            for (SharedParticleDefinition definition : definitions) {
                if (shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
                    continue;
                }
                int count = resolveEmissionCount(definition.count(), densityScale,
                        definition.offsetX(), definition.offsetY(), definition.offsetZ(), definition.extra());
                spawnForViewerResolvedCount(
                    viewer,
                    location,
                    definition.particle(),
                    count,
                    definition.offsetX(),
                    definition.offsetY(),
                    definition.offsetZ(),
                    definition.extra(),
                    definition.data()
                );
            }
        }
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。閲覧者は地点から64m以内に限定します。
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     */
    public void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnForNearbyViewers(location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    /**
     * 単発演出を表示します。固定点は一粒、散布は個数または表示頻度へ密度を適用します。閲覧者は地点から64m以内に限定します。
     * @param <T> 粒子追加データの型
     * @param location 表示する一地点
     * @param particle 共通resolverで解決済みの粒子種別
     * @param baseCount 標準の散布個数。固定座標の重複表示には使用しません
     * @param offsetX X方向の散布幅
     * @param offsetY Y方向の散布幅
     * @param offsetZ Z方向の散布幅
     * @param extra 粒子の速度などの追加パラメータ
     * @param data 種類に合う追加データ。不要な場合null
     */
    public <T> void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForNearbyViewers(location, particle, baseCount, offsetX, offsetY, offsetZ, extra, data, false);
    }

    private <T> void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data,
        boolean hideForBedrock
    ) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(location) > DEFAULT_VIEWER_DISTANCE_SQUARED) {
                continue;
            }
            if (shouldSkipForBedrock(viewer, hideForBedrock)) {
                continue;
            }
            spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, data);
        }
    }

    /**
     * 最大密度用の複数地点を、閲覧者走査一回と地点ごとの64m判定で表示します。
     *
     * @param center 描画対象worldを指定する中心
     * @param locations 表示対象地点
     * @param definition 表示するパーティクル定義
     */
    public void spawnForNearbyViewers(
        @NotNull Location center,
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition
    ) {
        World world = center.getWorld();
        if (world != null) spawnNearbyBatch(world, locations, definition, false, true);
    }

    /**
     * 広く離れた複数地点の粒子を、worldのviewer走査を1回にまとめて近傍だけへ表示します。
     *
     * @param locations 同一worldに属する表示地点
     * @param definition 表示する共通パーティクル定義
     */
    public void spawnForNearbyViewers(
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition
    ) {
        spawnForNearbyViewers(locations, definition, false);
    }

    /**
     * 地点ごとの64m近傍へ、必要に応じてクライアントの長距離粒子フラグ付きで表示します。
     * プレイヤーの粒子密度、OFF、統合版の非表示指定は従来と同じように適用します。
     * @param locations 同一worldの有限な表示地点
     * @param definition 種類・個数・追加データの共通定義
     * @param longDistance 広い戦闘予兆など、クライアントの拡張描画距離を要求する場合true
     */
    public void spawnForNearbyViewers(
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition,
        boolean longDistance
    ) {
        if (locations.isEmpty()) return;
        World world = locations.iterator().next().getWorld();
        if (world != null) spawnNearbyBatch(world, locations, definition, longDistance, true);
    }

    /**
     * 複数対象の中心など独立した発生源を保持し、閲覧者探索をまとめて単発演出を送ります。
     * 図形の候補点と異なり、地点数へ密度を適用せず各地点の散布個数・表示頻度へ適用します。
     * @param center 表示対象worldを指定する中心
     * @param locations 対象ごとの独立した発生源。同座標は一回だけ送ります
     * @param definition 単発演出の共通定義。OFFと統合版非表示指定を尊重します
     */
    public void spawnBurstsForNearbyViewers(
        @NotNull Location center,
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition
    ) {
        spawnBurstsForNearbyViewers(center, locations, definition, false);
    }

    /**
     * 独立した複数発生源を、拡張描画距離の指定を維持して一括表示します。
     * @param center 表示対象worldを指定する中心
     * @param locations 各対象の独立発生源。同座標を重複送信しません
     * @param definition 各発生源の単発演出
     * @param longDistance クライアントの拡張描画距離を要求する場合true
     */
    public void spawnBurstsForNearbyViewers(
        @NotNull Location center,
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition,
        boolean longDistance
    ) {
        World world = center.getWorld();
        if (world != null) spawnNearbyBatch(world, locations, definition, longDistance, false);
    }

    /**
     * 一地点の広い戦闘演出を密度設定と地点別の64m近傍判定を維持して表示します。
     * @param location 表示する一地点
     * @param definition パーティクルの共通定義
     * @param longDistance クライアントの拡張描画距離を要求する場合true
     */
    public void spawnForNearbyViewers(
        @NotNull Location location, @NotNull SharedParticleDefinition definition, boolean longDistance
    ) {
        World world = location.getWorld();
        if (world == null) return;
        for (Player viewer : world.getNearbyPlayers(location, Math.sqrt(DEFAULT_VIEWER_DISTANCE_SQUARED))) {
            if (viewer.getLocation().distanceSquared(location) > DEFAULT_VIEWER_DISTANCE_SQUARED
                    || shouldSkipForBedrock(viewer, definition.hideForBedrock())) continue;
            int count = resolveEmissionCount(definition.count(), resolvePlayerDensityScale(viewer),
                    definition.offsetX(), definition.offsetY(), definition.offsetZ(), definition.extra());
            spawnForViewerResolvedCount(viewer, location, definition.particle(), count,
                    definition.offsetX(), definition.offsetY(), definition.offsetZ(),
                    definition.extra(), definition.data(), longDistance);
        }
    }

    /**
     * 点群の重複除去と閲覧者走査を一回にまとめ、各地点の近傍へ表示します。
     * @param world 表示対象world
     * @param locations 最大密度用の候補点
     * @param definition 表示定義
     * @param longDistance クライアントの拡張描画距離を使う場合true
     * @param geometry 地点を図形の候補点として間引く場合true、独立発生源として維持する場合false
     */
    private void spawnNearbyBatch(World world, Collection<Location> locations,
                                  SharedParticleDefinition definition, boolean longDistance, boolean geometry) {
        if (locations.isEmpty() || definition.count() <= 0) return;
        List<Player> viewers = world.getPlayers();
        if (viewers.isEmpty()) return;
        List<Location> points = uniqueLocations(locations, world);
        if (points.isEmpty()) return;
        for (Player viewer : viewers) {
            double density = resolvePlayerDensityScale(viewer);
            if (density > 0.0D && !shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
                if (geometry) spawnBatch(viewer, points, definition, density, longDistance, true);
                else spawnBurstBatch(viewer, points, definition, density, longDistance, true);
            }
        }
    }

    /**
     * 独立した各発生源の近傍へ、単発散布の密度契約で表示します。
     * @param viewer 表示先
     * @param points 重複を除いた独立発生源
     * @param definition 共通表示定義
     * @param density 閲覧者の密度
     * @param longDistance クライアントの拡張描画距離を使う場合true
     * @param checkDistance 地点ごとの64m判定を行う場合true
     */
    private void spawnBurstBatch(Player viewer, List<Location> points, SharedParticleDefinition definition,
                                 double density, boolean longDistance, boolean checkDistance) {
        Location viewerLocation = checkDistance ? viewer.getLocation() : null;
        boolean bedrock = isBedrock(viewer);
        if (bedrock && viewerLocation != null) {
            List<Location> nearby = new ArrayList<>();
            for (Location point : points) {
                if (viewerLocation.distanceSquared(point) <= DEFAULT_VIEWER_DISTANCE_SQUARED) nearby.add(point);
            }
            points = nearby;
        }
        if (bedrock && points.size() > BEDROCK_MAX_POINTS) {
            List<Location> sampled = new ArrayList<>(BEDROCK_MAX_POINTS);
            for (int ordinal = 0; ordinal < BEDROCK_MAX_POINTS; ordinal++) {
                sampled.add(points.get(ParticlePointSampling.index(ordinal, points.size(), BEDROCK_MAX_POINTS)));
            }
            points = sampled;
        }
        int checked = 0;
        for (Location point : points) {
            if (viewerLocation != null && viewerLocation.distanceSquared(point) > DEFAULT_VIEWER_DISTANCE_SQUARED) continue;
            if (checked++ >= ParticlePointSampling.MAX_POINTS) break;
            int count = resolveEmissionCount(definition.count(), density, definition.offsetX(), definition.offsetY(),
                    definition.offsetZ(), definition.extra());
            spawnForViewerResolvedCount(viewer, point, definition.particle(), count,
                    definition.offsetX(), definition.offsetY(), definition.offsetZ(), definition.extra(), definition.data(), longDistance);
        }
    }

    /**
     * 同じworld内の有限座標だけを残し、向きが違う地点や百万分の一ブロック未満の誤差も一つへまとめます。
     * @param locations 最大密度用の候補点。別パーツを交互に追加せず形状順に並べます
     * @param world 表示先world
     * @return 元の順番を維持する重複のない地点
     */
    private List<Location> uniqueLocations(Collection<Location> locations, World world) {
        Map<PointKey, Location> points = new LinkedHashMap<>();
        for (Location location : locations) {
            if (location.getWorld() != world || !Double.isFinite(location.getX())
                    || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())) continue;
            PointKey key = new PointKey(Math.round(location.getX() * 1.0E6D),
                    Math.round(location.getY() * 1.0E6D), Math.round(location.getZ() * 1.0E6D));
            points.putIfAbsent(key, location);
        }
        return new ArrayList<>(points.values());
    }

    /**
     * 一人の閲覧者へ点群を空間的に間引いて送ります。各地点の個数へ密度を二重適用しません。
     * @param viewer 同一worldの閲覧者
     * @param points 重複のない最大密度用の候補点
     * @param definition 各地点のパーティクル定義
     * @param density 標準を1とする閲覧者密度
     * @param longDistance クライアントの拡張描画距離指定
     * @param checkDistance 地点ごとの64m判定も行う場合true
     */
    private void spawnBatch(Player viewer, List<Location> points, SharedParticleDefinition definition,
                            double density, boolean longDistance, boolean checkDistance) {
        if (definition.count() <= 0 || points.isEmpty()) return;
        if (checkDistance) {
            Location viewerLocation = viewer.getLocation();
            List<Location> nearby = new ArrayList<>();
            for (Location point : points) {
                if (viewerLocation.distanceSquared(point) <= DEFAULT_VIEWER_DISTANCE_SQUARED) nearby.add(point);
            }
            points = nearby;
        }
        int selected = ParticlePointSampling.count(points.size(), density * PLUGIN_PARTICLE_DENSITY_SCALE);
        boolean bedrock = isBedrock(viewer);
        if (bedrock) selected = Math.min(selected, BEDROCK_MAX_POINTS);
        if (selected == 0) return;
        int count = bedrock || isFixedPoint(definition.offsetX(), definition.offsetY(), definition.offsetZ(), definition.extra())
                ? 1 : definition.count();
        for (int ordinal = 0; ordinal < selected; ordinal++) {
            Location point = points.get(ParticlePointSampling.index(ordinal, points.size(), selected));
            spawnForViewerResolvedCount(viewer, point, definition.particle(), count,
                    bedrock ? 0.0D : definition.offsetX(), bedrock ? 0.0D : definition.offsetY(),
                    bedrock ? 0.0D : definition.offsetZ(),
                    bedrock ? 0.0D : definition.extra(), definition.data(), longDistance);
        }
    }

    /**
     * 単発散布は個数、固定点は表示頻度へ密度を適用し、同座標の重複を防ぎます。
     * @param baseCount 標準の散布個数
     * @param density 標準を1とする閲覧者密度
     * @param offsetX X方向の拡散
     * @param offsetY Y方向の拡散
     * @param offsetZ Z方向の拡散
     * @param extra 粒子の速度などの追加パラメータ
     * @return 送信個数。0は送信なし
     */
    private int resolveEmissionCount(int baseCount, double density, double offsetX, double offsetY,
                                     double offsetZ, double extra) {
        density *= PLUGIN_PARTICLE_DENSITY_SCALE;
        if (baseCount <= 0 || !Double.isFinite(density) || density <= 0) return 0;
        if (isFixedPoint(offsetX, offsetY, offsetZ, extra)) {
            return density >= 1.0D || ThreadLocalRandom.current().nextDouble() < density ? 1 : 0;
        }
        double scaled = baseCount * Math.min(ParticlePointSampling.MAX_DENSITY, density);
        int whole = (int) Math.min(Integer.MAX_VALUE, scaled);
        double fraction = scaled - whole;
        return whole == Integer.MAX_VALUE || fraction <= 0.0D ? whole
                : whole + (ThreadLocalRandom.current().nextDouble() < fraction ? 1 : 0);
    }

    /**
     * 拡散も移動速度もなく同座標に重なる定義かを判定します。
     * @param offsetX X方向の拡散
     * @param offsetY Y方向の拡散
     * @param offsetZ Z方向の拡散
     * @param extra 粒子の追加パラメータ
     * @return 四値がすべて0の場合true
     */
    private boolean isFixedPoint(double offsetX, double offsetY, double offsetZ, double extra) {
        return offsetX == 0.0D && offsetY == 0.0D && offsetZ == 0.0D && extra == 0.0D;
    }

    private record PointKey(long x, long y, long z) { }

    private double resolvePlayerDensityScale(@NotNull AstPlayer astPlayer) {
        if (playerSettingService == null) {
            return ParticleDensity.NORMAL.getDensityScale();
        }
        return playerSettingService.getParticleDensityScale(astPlayer.getUser().getUuid());
    }

    private double resolvePlayerDensityScale(@NotNull Player viewer) {
        if (playerSettingService == null) return ParticleDensity.NORMAL.getDensityScale();
        AstPlayer astPlayer = AstPlayerCache.get(viewer);
        if (astPlayer == null) {
            return ParticleDensity.NORMAL.getDensityScale();
        }
        return resolvePlayerDensityScale(astPlayer);
    }

    private boolean shouldSkipForBedrock(@NotNull AstPlayer viewer, boolean hideForBedrock) {
        return hideForBedrock && viewer.isBedrock();
    }

    private boolean shouldSkipForBedrock(@NotNull Player viewer, boolean hideForBedrock) {
        return hideForBedrock && isBedrock(viewer);
    }

    /** 表示対象の登録済み接続種別を返します。未登録の接続は従来どおりJava版として扱います。 */
    private boolean isBedrock(@NotNull Player viewer) {
        AstPlayer astPlayer = AstPlayerCache.get(viewer);
        return astPlayer != null && astPlayer.isBedrock();
    }

    private <T> void spawnForViewerResolvedCount(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int count,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForViewerResolvedCount(viewer, location, particle, count, offsetX, offsetY, offsetZ, extra, data, false);
    }

    /** Java版は元の粒子、統合版は個数を抑えた小型ダストを同じ座標へ送信します。 */
    private <T> void spawnForViewerResolvedCount(
        @NotNull Player viewer, @NotNull Location location, @NotNull Particle particle, int count,
        double offsetX, double offsetY, double offsetZ, double extra, @Nullable T data, boolean longDistance
    ) {
        if (count <= 0 || location.getWorld() != viewer.getWorld()) {
            return;
        }
        if (isBedrock(viewer)) {
            viewer.spawnParticle(SharedParticleDefinitions.BEDROCK_NEUTRAL.particle(), location,
                Math.min(count, BEDROCK_MAX_BURST_COUNT), offsetX, offsetY, offsetZ, 0.0D,
                SharedParticleDefinitions.bedrockDust(particle, data), longDistance);
            return;
        }
        viewer.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data, longDistance);
    }
}
