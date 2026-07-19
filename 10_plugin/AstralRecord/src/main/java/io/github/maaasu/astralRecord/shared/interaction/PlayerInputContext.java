package io.github.maaasu.astralRecord.shared.interaction;

import java.util.Objects;
import java.util.UUID;

/**
 * 1回のプレイヤー入力を調停するための不変コンテキストです。
 * gateway固有の情報は、Bukkit APIへ依存しない不変snapshotとして型引数へ渡します。
 *
 * @param playerId 入力元プレイヤーのUUID
 * @param inputSequence gatewayが付与した入力系列番号
 * @param family 入力ファミリー
 * @param source 入力を観測した入口
 * @param inputSnapshot gateway固有の不変入力snapshot
 * @param <T> gateway固有snapshotの型
 */
public record PlayerInputContext<T>(
    UUID playerId,
    long inputSequence,
    InputFamily family,
    InputSource source,
    T inputSnapshot
) {
    /**
     * 入力コンテキストを生成します。
     */
    public PlayerInputContext {
        playerId = Objects.requireNonNull(playerId, "playerId");
        family = Objects.requireNonNull(family, "family");
        source = Objects.requireNonNull(source, "source");
        inputSnapshot = Objects.requireNonNull(inputSnapshot, "inputSnapshot");
        if (inputSequence < 0L) {
            throw new IllegalArgumentException("inputSequence must be zero or greater");
        }
    }
}
