package io.github.maaasu.astralRecord.feature.account.model

import java.util.UUID

/** API によるアカウント削除結果。 */
data class AccountDeleteResult(
    val deletedAccountId: UUID,
    val userId: UUID,
    val deletedSlotIndex: Int,
    val selectedAccountId: UUID,
    val createdReplacement: Boolean,
)
