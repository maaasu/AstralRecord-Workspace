package io.github.maaasu.astralRecord.feature.market.event;

import io.github.maaasu.astralRecord.feature.market.model.MarketTransaction;
import io.github.maaasu.astralRecord.feature.market.repository.MarketTransportException;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/** 同じ購入要求の結果が確定するまで receipt と通信結果不明状態を保持します。 */
final class MarketPurchaseRecoveryState {
    private MarketTransaction transaction;
    private boolean outcomeMayBeUnknown;

    /** 結果不明時だけ同じ冪等キーの要求を再送し、確定した receipt は以後再利用します。 */
    synchronized @NotNull MarketTransaction resolve(@NotNull Supplier<MarketTransaction> purchase) {
        if (transaction != null) return transaction;
        try {
            transaction = purchase.get();
        } catch (MarketTransportException firstFailure) {
            outcomeMayBeUnknown = true;
            try {
                transaction = purchase.get();
            } catch (RuntimeException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
        return transaction;
    }

    /** 最初の確定拒否だけは API 未成立として保存境界を解除できます。 */
    synchronized boolean canAbandonOnRejection() {
        return !outcomeMayBeUnknown && transaction == null;
    }
}
