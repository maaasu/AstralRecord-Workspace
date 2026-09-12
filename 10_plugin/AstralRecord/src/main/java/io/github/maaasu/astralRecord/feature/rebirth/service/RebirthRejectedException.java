package io.github.maaasu.astralRecord.feature.rebirth.service;

import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthRejectionReason;
import org.jetbrains.annotations.NotNull;

/** 転生操作が現在の状態では成立しないことを表します。 */
public final class RebirthRejectedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final RebirthRejectionReason reason;

    /**
     * 拒否理由を保持する例外を生成します。
     *
     * @param reason 転生操作の拒否理由
     */
    public RebirthRejectedException(@NotNull RebirthRejectionReason reason) {
        super("Rebirth operation was rejected: " + reason);
        this.reason = reason;
    }

    /** @return 転生操作の拒否理由 */
    public @NotNull RebirthRejectionReason reason() {
        return reason;
    }
}
