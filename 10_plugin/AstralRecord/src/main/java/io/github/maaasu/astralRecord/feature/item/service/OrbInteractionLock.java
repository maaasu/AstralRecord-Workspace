package io.github.maaasu.astralRecord.feature.item.service;

import org.jetbrains.annotations.NotNull;

/**
 * オーブ装備更新中の排他状態を保持する小さな状態機械です。
 */
final class OrbInteractionLock {

    private Phase phase = Phase.READY;

    /**
     * オーブ消費後の装備更新待機へ進めます。
     */
    void beginMutation() {
        if (phase != Phase.CLOSED) {
            phase = Phase.MUTATING;
        }
    }

    /**
     * 更新完了後に通常操作を再許可します。
     */
    void release() {
        if (phase != Phase.CLOSED) {
            phase = Phase.READY;
        }
    }

    /**
     * セッション終了を記録し、以後の段階変更を無視します。
     */
    void close() {
        phase = Phase.CLOSED;
    }

    /**
     * 現在段階でクリック系操作を拒否すべきか判定します。
     *
     * @return 装備更新中なら {@code true}
     */
    boolean isLocked() {
        return phase == Phase.MUTATING;
    }

    /**
     * テストと診断向けに現在段階を返します。
     *
     * @return 現在段階
     */
    @NotNull Phase phase() {
        return phase;
    }

    /** オーブ操作の排他的な進行段階です。 */
    enum Phase {
        READY,
        MUTATING,
        CLOSED,
    }
}
