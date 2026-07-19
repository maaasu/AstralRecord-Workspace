package io.github.maaasu.astralRecord.shared.interaction;

import java.util.Objects;
import java.util.Optional;

/**
 * 入力調停の結果です。
 *
 * @param winner 選択された候補。候補なしの場合は空
 */
public record PlayerInputDispatchResult(Optional<PlayerInputCandidate> winner) {
    /**
     * 入力調停結果を生成します。
     */
    public PlayerInputDispatchResult {
        winner = Objects.requireNonNull(winner, "winner");
    }

    /**
     * 候補が存在しなかった結果を返します。
     *
     * @return pass-through結果
     */
    public static PlayerInputDispatchResult passThrough() {
        return new PlayerInputDispatchResult(Optional.empty());
    }

    /**
     * 勝者候補を持つ結果を返します。
     *
     * @param winner 実行済みの勝者候補
     * @return 勝者ありの結果
     */
    public static PlayerInputDispatchResult selected(PlayerInputCandidate winner) {
        return new PlayerInputDispatchResult(Optional.of(Objects.requireNonNull(winner, "winner")));
    }

    /**
     * 候補が選択されたか返します。
     *
     * @return 勝者が存在する場合はtrue
     */
    public boolean hasWinner() {
        return winner.isPresent();
    }

    /**
     * gatewayへ返すclaim/cancel方針を返します。
     * 候補なしの場合はpass-throughです。
     *
     * @return 入力反映方針
     */
    public InputClaimPolicy claimPolicy() {
        return winner.map(PlayerInputCandidate::claimPolicy).orElse(InputClaimPolicy.PASS_THROUGH);
    }

    /**
     * 入力を処理済みとして記録するか返します。
     *
     * @return claimする場合はtrue
     */
    public boolean isClaimed() {
        return claimPolicy().isClaimed();
    }

    /**
     * 元イベントをcancelするか返します。
     *
     * @return cancelを要求する場合はtrue
     */
    public boolean isCancelRequested() {
        return claimPolicy().isCancelRequested();
    }
}
