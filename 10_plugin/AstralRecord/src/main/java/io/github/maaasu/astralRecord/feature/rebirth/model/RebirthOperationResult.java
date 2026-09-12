package io.github.maaasu.astralRecord.feature.rebirth.model;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import org.jetbrains.annotations.NotNull;

/**
 * 転生の開始または有償終了で確定したアカウント進行を表します。
 *
 * @param account 更新後アカウント
 * @param originalLevel 転生前レベル
 * @param consumedAmount 消費した通貨量
 */
public record RebirthOperationResult(
    @NotNull AccountModel account,
    int originalLevel,
    long consumedAmount
) {
}
