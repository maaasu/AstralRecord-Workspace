package io.github.maaasu.astralRecord.feature.user.model

import java.util.UUID

/**
 * システム操作を表す仮想ユーザー定数。
 * プレイヤーによる操作ではなくシステム（プラグイン）が行う登録・更新処理において、
 * `createdBy` / `updatedBy` に使用します。
 *
 * UUID は固定値 `00000000-0000-0000-0000-000000000000`（nil UUID）を使用します。
 */
object SystemUser {
    /** システム操作を示す固定 UUID（nil UUID）。 */
    val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
}



