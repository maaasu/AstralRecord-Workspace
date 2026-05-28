package io.github.maaasu.astralRecord.shared.effect;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * パーティクル送信量をプラグイン設定の密度で補正して表示するサービス。
 * 将来的なプレイヤー別密度設定に備え、プレイヤー単位の補正係数も受け取れる。
 */
public class ParticleDisplayService {

    private static final double PLUGIN_PARTICLE_DENSITY_SCALE = 1.0D;

    private final PlayerSettingService playerSettingService;

    public ParticleDisplayService() {
        this(null);
    }

    /**
     * プレイヤー設定サービスを参照してパーティクル表示サービスを構築します。
     *
     * @param playerSettingService プレイヤー設定サービス。未初期化時は標準密度を使用します。
     */
    public ParticleDisplayService(@Nullable PlayerSettingService playerSettingService) {
        this.playerSettingService = playerSettingService;
    }

    /**
     * 対象プレイヤーの設定密度を反映してワールド向けにパーティクルを表示します。
     *
     * @param astPlayer 密度設定の参照元プレイヤー
     * @param world ワールド
     * @param location 表示座標
     * @param particle パーティクル種別
     * @param baseCount 基準個数
     * @param offsetX X拡散
     * @param offsetY Y拡散
     * @param offsetZ Z拡散
     * @param extra 追加パラメータ
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
        spawnWorld(world, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, resolvePlayerDensityScale(astPlayer));
    }

    /**
     * ワールド向けにパーティクルを表示する。
     *
     * @param world ワールド
     * @param location 表示座標
     * @param particle パーティクル種別
     * @param baseCount 基準個数
     * @param offsetX X拡散
     * @param offsetY Y拡散
     * @param offsetZ Z拡散
     * @param extra 追加パラメータ
     * @param playerDensityScale プレイヤー個別の密度倍率（未設定時は 1.0）
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
        int count = resolveCount(baseCount, playerDensityScale);
        if (count <= 0) {
            return;
        }
        world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
    }

    /**
     * 指定プレイヤーにのみパーティクルを送信する。
     *
     * @param viewer 送信先プレイヤー
     * @param location 表示座標
     * @param particle パーティクル種別
     * @param baseCount 基準個数
     * @param offsetX X拡散
     * @param offsetY Y拡散
     * @param offsetZ Z拡散
     * @param extra 追加パラメータ
     * @param playerDensityScale プレイヤー個別の密度倍率（未設定時は 1.0）
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
        spawnForViewer(viewer.getBukkit(), location, particle, baseCount, offsetX, offsetY, offsetZ, extra, resolvePlayerDensityScale(viewer));
    }

    /**
     * 指定のプレイヤーにのみパーティクルを送信する。
     *
     * @param viewer 送信先プレイヤー
     * @param location 表示座標
     * @param particle パーティクル種別
     * @param baseCount 基準個数
     * @param offsetX X拡散
     * @param offsetY Y拡散
     * @param offsetZ Z拡散
     * @param extra 追加パラメータ
     * @param playerDensityScale プレイヤー個別の密度倍率（未設定時は 1.0）
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
        int count = resolveCount(baseCount, playerDensityScale);
        if (count <= 0) {
            return;
        }
        viewer.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
    }

    private double resolvePlayerDensityScale(@NotNull AstPlayer astPlayer) {
        if (playerSettingService == null) {
            return ParticleDensity.NORMAL.getDensityScale();
        }
        return playerSettingService.getParticleDensityScale(astPlayer.getUser().getUuid());
    }

    private int resolveCount(int baseCount, double playerDensityScale) {
        if (baseCount <= 0) {
            return 0;
        }
        double pluginDensity = Math.max(0.0D, PLUGIN_PARTICLE_DENSITY_SCALE);
        double effectiveDensity = pluginDensity * Math.max(0.0D, playerDensityScale);
        return Math.max(0, (int) Math.round(baseCount * effectiveDensity));
    }
}
