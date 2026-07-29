package io.github.maaasu.astralarchitect.ticket;

/**
 * 建築チケットのライフサイクル状態です。
 */
public enum TicketState {
    CREATING,
    CREATED,
    READY,
    APPLYING,
    APPLIED,
    ROLLING_BACK,
    ROLLED_BACK,
    TRASHED;

    /**
     * AI候補を検証できる状態か判定します。
     *
     * @return 検証可能な場合はtrue
     */
    public boolean canValidate() {
        return this == CREATED || this == READY || this == ROLLED_BACK;
    }
}
