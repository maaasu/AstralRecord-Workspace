package io.github.maaasu.astralRecord.feature.player.model

import io.github.maaasu.astralRecord.feature.account.model.AccountModel
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot
import io.github.maaasu.astralRecord.feature.user.model.UserModel
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player

/**
 * AstralRecord プロジェクト独自のプレイヤーモデル。
 * Bukkit の [Player] と区別するため `Ast` プレフィックスを付与しています。
 *
 * プレイヤー一人に紐づく各種データを一元的に保持します。
 *
 * @property bukkit   Bukkit プレイヤーインスタンス
 * @property user     dbo.user に対応するユーザーデータ（permission 変更時は [applyPermission] 経由で更新すること）
 * @property account  現在アクティブな dbo.account に対応するアカウントデータ
 *
 * status 関連の計算結果は [statusSnapshot] に保持し、オンライン中のセッションデータとして扱います。
 */
data class AstPlayer(
    val bukkit: Player,
    var user: UserModel,
    var account: AccountModel,
) {
    var statusSnapshot: StatusSnapshot = StatusSnapshot.empty()
    val activeBuffs: MutableList<ActiveBuff> = mutableListOf()

    /**
     * しゃがみ開始時刻（System.currentTimeMillis ベース）。
     * しゃがみ開始 → 短時間以内に解除でドッジ判定に使用します。
     */
    var sneakStartedAtMs: Long = 0L

    /**
     * 次にドッジを発動できる時刻（System.currentTimeMillis ベース）。
     * クールダウン中はドッジを発動できません。
     */
    var dodgeCooldownEndAtMs: Long = 0L

    /**
     * ドッジ実行中フラグ。
     * 攻撃処理側でジャスト回避判定に使用するため、常に最新の状態へ同期する必要があります。
     */
    var isDodging: Boolean = false

    init {
        applyPermission(user)
        applyAccountMode(account)
    }

    companion object {
        /** この値以上の permission を持つプレイヤーに Minecraft OP 権限を付与する */
        const val OP_PERMISSION_THRESHOLD = 99
    }

    /**
     * このプレイヤーを Bedrock Edition プレイヤーとして扱うべきか判定します。
     *
     * @return Bedrock Edition プレイヤーとして扱うなら true
     */
    fun isBedrock(): Boolean {
        val prefixes = ConfigProperties.getInstance().resourcePackBedrockNamePrefixes ?: return false
        return prefixes.any { prefix ->
            !prefix.isNullOrBlank() && bukkit.name.startsWith(prefix)
        }
    }

    /**
     * [UserModel] の permission を新しい値で更新し、Minecraft OP 権限を同期します。
     * <p>
     * permission >= [OP_PERMISSION_THRESHOLD] の場合は [bukkit].setOp(true)、
     * それ以外の場合は [bukkit].setOp(false) を設定します。
     *
     * @param newUser permission が更新された新しい [UserModel]
     */
    fun applyPermission(newUser: UserModel) {
        user = newUser
        if (newUser.permission >= OP_PERMISSION_THRESHOLD) {
            bukkit.isOp = true
            Logger.log(LogId.I_5070, bukkit.name, newUser.permission)
            sendMessage(PlayerMsgId.P_5070, newUser.permission)
        } else {
            bukkit.isOp = false
        }
    }

    /**
     * [AccountModel] の mode を Bukkit プレイヤーへ反映します。
     * mode が [AccountMode.PLAYER] の場合、通常プレイヤー向け制限として
     * GameMode を [GameMode.ADVENTURE]、エンティティ/ブロックの reach を 0 に設定します。
     *
     * @param newAccount 反映対象の [AccountModel]
     */
    fun applyAccountMode(newAccount: AccountModel) {
        account = newAccount
        if (newAccount.mode != AccountMode.PLAYER) {
            applyReach(Attribute.ENTITY_INTERACTION_RANGE, 3.0)
            applyReach(Attribute.BLOCK_INTERACTION_RANGE, 4.5)
            return
        }

        bukkit.gameMode = GameMode.ADVENTURE
        applyReach(Attribute.ENTITY_INTERACTION_RANGE, 0.0)
        applyReach(Attribute.BLOCK_INTERACTION_RANGE, 0.0)
    }

    /**
     * 指定 Attribute の baseValue を更新します。
     */
    private fun applyReach(attribute: Attribute, value: Double) {
        bukkit.getAttribute(attribute)?.baseValue = value
    }

    /**
     * プレイヤーにメッセージを送信します。
     * メッセージ文言は [PlayerMsgId] 経由で [PlayerMsgResource] から取得されます。
     *
     * @param msgId メッセージID
     * @param args  フォーマット引数（省略可）
     */
    fun sendMessage(msgId: PlayerMsgId, vararg args: Any) {
        if (!bukkit.isOnline) return
        val message = PlayerMsgResource.format(msgId.id, *args)
        bukkit.sendMessage(message)
    }
}
