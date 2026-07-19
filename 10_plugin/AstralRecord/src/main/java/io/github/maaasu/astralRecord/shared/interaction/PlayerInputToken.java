package io.github.maaasu.astralRecord.shared.interaction;

import java.util.Objects;
import java.util.UUID;

/**
 * 同じ物理入力から配送された複数の Bukkit イベントを結び付ける論理入力トークンです。
 *
 * @param playerId 入力元プレイヤー UUID
 * @param serverTick 入力を最初に観測したサーバー tick
 * @param sequence プラグイン内で単調増加する入力系列番号
 * @param family 入力ファミリー
 */
public record PlayerInputToken(
    UUID playerId,
    int serverTick,
    long sequence,
    InputFamily family
) {
    /**
     * 入力トークンを生成します。
     */
    public PlayerInputToken {
        playerId = Objects.requireNonNull(playerId, "playerId");
        family = Objects.requireNonNull(family, "family");
        if (serverTick < 0) {
            throw new IllegalArgumentException("serverTick must be zero or greater");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be zero or greater");
        }
    }
}
