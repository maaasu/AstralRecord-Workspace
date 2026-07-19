package io.github.maaasu.astralRecord.shared.interaction;

import java.util.Collection;

/**
 * 入力コンテキストから、副作用を起こさずに実行候補を解決します。
 *
 * @param <T> gateway固有snapshotの型
 */
@FunctionalInterface
public interface PlayerInputResolver<T> {
    /**
     * 入力に該当する候補を返します。
     * このメソッド内ではゲーム状態を変更せず、変更処理は候補executorへ格納します。
     *
     * @param context 入力コンテキスト
     * @return 0件以上の候補。nullは許可しない
     */
    Collection<PlayerInputCandidate> resolve(PlayerInputContext<T> context);
}
