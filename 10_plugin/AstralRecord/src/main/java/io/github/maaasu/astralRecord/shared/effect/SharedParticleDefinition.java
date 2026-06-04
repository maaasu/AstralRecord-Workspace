package io.github.maaasu.astralRecord.shared.effect;

import org.bukkit.Particle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 共通利用するパーティクル表示定義を表します。
 *
 * @param id 定義 ID
 * @param particle パーティクル種別
 * @param count 表示個数
 * @param offsetX X 方向の拡散量
 * @param offsetY Y 方向の拡散量
 * @param offsetZ Z 方向の拡散量
 * @param extra 追加パラメータ
 * @param data パーティクル追加データ。不要な場合は {@code null}
 */
public record SharedParticleDefinition(
    @NotNull String id,
    @NotNull Particle particle,
    int count,
    double offsetX,
    double offsetY,
    double offsetZ,
    double extra,
    @Nullable Object data
) {

    /**
     * 追加データなしの共通定義を生成します。
     *
     * @param id 定義 ID
     * @param particle パーティクル種別
     * @param count 表示個数
     * @param offsetX X 方向の拡散量
     * @param offsetY Y 方向の拡散量
     * @param offsetZ Z 方向の拡散量
     * @param extra 追加パラメータ
     */
    public SharedParticleDefinition(
        @NotNull String id,
        @NotNull Particle particle,
        int count,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        this(id, particle, count, offsetX, offsetY, offsetZ, extra, null);
    }

    /**
     * 表示個数だけを差し替えた定義を返します。
     *
     * @param updatedCount 新しい表示個数
     * @return 個数を差し替えた定義
     */
    public @NotNull SharedParticleDefinition withCount(int updatedCount) {
        return new SharedParticleDefinition(id, particle, updatedCount, offsetX, offsetY, offsetZ, extra, data);
    }
}
