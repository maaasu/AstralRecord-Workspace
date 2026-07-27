package io.github.maaasu.astralRecord.shared.interaction;

/**
 * 勝者候補を実行した後、gatewayが元イベントへ反映する方針です。
 */
public enum InputClaimPolicy {
    /**
     * 元イベントをclaimもcancelもしません。
     * 現在は候補なしのdispatch結果を表す既定方針として使用します。
     */
    PASS_THROUGH(false, false),
    /** 元イベントを処理済みとしてclaimしますが、cancelは要求しません。 */
    CLAIM(true, false),
    /** 元イベントを処理済みとしてclaimし、cancelも要求します。 */
    CLAIM_AND_CANCEL(true, true);

    private final boolean claimed;
    private final boolean cancelRequested;

    InputClaimPolicy(boolean claimed, boolean cancelRequested) {
        this.claimed = claimed;
        this.cancelRequested = cancelRequested;
    }

    /**
     * 入力を処理済みとして記録するか返します。
     *
     * @return claimする場合はtrue
     */
    public boolean isClaimed() {
        return claimed;
    }

    /**
     * gatewayへ元イベントのcancelを要求するか返します。
     *
     * @return cancelを要求する場合はtrue
     */
    public boolean isCancelRequested() {
        return cancelRequested;
    }
}
