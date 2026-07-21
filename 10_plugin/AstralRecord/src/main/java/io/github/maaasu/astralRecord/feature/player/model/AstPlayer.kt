package io.github.maaasu.astralRecord.feature.player.model

import io.github.maaasu.astralRecord.feature.account.model.AccountModel
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.feature.account.model.ClassProgressModel
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId
import io.github.maaasu.astralRecord.feature.player.GameModeChangeGuard
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot
import io.github.maaasu.astralRecord.feature.user.model.UserModel
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties
import io.github.maaasu.astralRecord.feature.resourcepack.service.BedrockPlayerDetector
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationState
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.util.Vector

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

    /** GUI を閉じずに遷移した現在画面と戻り先のセッション履歴。 */
    val guiNavigationState: GuiNavigationState = GuiNavigationState()

    /** 現在の職業 ID。未設定の場合はデフォルト職業 "adventurer"。 */
    private val classProgressById: MutableMap<String, ClassProgressModel> = linkedMapOf()

    var classId: String = normalizeClassId(account.classId)
        private set

    /** 現在の職業レベル。 */
    var classLevel: Int
        get() = getClassProgress(classId).level
        set(value) = updateClassProgress(classId, value, classExperience)

    /** 現在クラスで獲得した累計クラス経験値。 */
    var classExperience: Long
        get() = getClassProgress(classId).experience
        set(value) = updateClassProgress(classId, classLevel, value)

    /**
     * 現在いる地域の表示名。
     * ワールド移動時はワールド種別の既定地域、オーバーワールド内では近接 Mob スポナーの地域を保持します。
     */
    var currentRegion: String? = null

    /**
     * 現在地域の推奨レベル。
     * オーバーワールドでは地域スポナーの出現 Mob 平均レベルを保持し、その他のワールドでは 0 を既定値とします。
     */
    var currentRegionLevel: Int = 0

    /**
     * しゃがみ開始時刻（System.currentTimeMillis ベース）。
     * しゃがみ開始 → 短時間以内に解除でドッジ判定に使用します。
     */
    var sneakStartedAtMs: Long = 0L

    /**
     * しゃがみ開始時のプレイヤー座標。
     * しゃがみ開始から解除までの座標差分をドッジ移動ベクトルの方向として使用します。
     */
    var sneakStartedAtLocation: Location? = null

    /**
     * ドッジ受付ウィンドウの終了時刻（System.currentTimeMillis ベース）。
     * HUD 上のドッジ受付バー表示の終了判定に使用します。
     */
    var sneakDodgeWindowExpiresAtMs: Long = 0L

    /**
     * 直近のジャンプ入力押下状態です。
     * [PlayerInputEvent] の立ち上がり検知に使用します。
     */
    var isJumpInputPressed: Boolean = false

    /**
     * 現在の滞空中で二段ジャンプを消費済みかを表します。
     * 着地時に解除し、空中での再入力では再利用できないようにします。
     */
    var isAirJumpConsumed: Boolean = false

    /**
     * 二段ジャンプを再生可能になる時刻（epoch ms）。
     * 地上で通常ジャンプを押した直後は、この時刻を過ぎるまでは二段ジャンプを許可しない。
     */
    var doubleJumpCooldownUntilMs: Long = 0L

    /**
     * 壁張り付き猶予の終了時刻（System.currentTimeMillis ベース）。
     * HUD 上の壁張り付きバー表示と、自然落下復帰の判定に使用します。
     */
    var wallClingExpiresAtMs: Long = 0L

    /**
     * 壁張り付き中フラグです。
     * 重力停止とスニーク解除時の壁キック判定に使用します。
     */
    var isWallClinging: Boolean = false

    /**
     * 壁張り付き時にプレイヤー正面にあった壁方向です。
     * スニーク解除時に「壁から十分背を向けたか」を判定する基準に使用します。
     */
    var wallClingTowardWallDirection: Vector? = null

    /**
     * ドッジ実行中フラグ。
     * 攻撃処理側でジャスト回避判定に使用するため、常に最新の状態へ同期する必要があります。
     */
    var isDodging: Boolean = false

    /** スキル詠唱が終了する予定時刻（System.currentTimeMillis ベース）。 */
    var skillCastingUntilMs: Long = 0L

    init {
        account.classProgresses.forEach { progress ->
            updateClassProgress(progress.classId, progress.level, progress.experience)
        }
        if (classProgressById.keys.none { it.equals(classId, ignoreCase = true) }) {
            updateClassProgress(classId, account.classLevel, account.classExperience)
        }
        applyPermission(user)
        applyAccountMode(account)
    }

    /**
     * 指定クラスを現在クラスに切り替えます。未経験のクラスは Lv.1 / EXP 0 で開始します。
     *
     * @param newClassId 切り替え先クラス ID
     */
    fun selectClass(newClassId: String) {
        classId = normalizeClassId(newClassId)
        getClassProgress(classId)
    }

    /**
     * 指定クラスの進行度を返します。未経験のクラスは Lv.1 / EXP 0 として登録します。
     *
     * @param targetClassId 参照するクラス ID
     * @return クラス進行度
     */
    fun getClassProgress(targetClassId: String): ClassProgressModel {
        val normalized = normalizeClassId(targetClassId)
        val existingKey = classProgressById.keys.firstOrNull { it.equals(normalized, ignoreCase = true) }
        if (existingKey != null) {
            return classProgressById.getValue(existingKey)
        }
        return ClassProgressModel(normalized).also { classProgressById[normalized] = it }
    }

    /**
     * 保持している全クラス進行度を返します。
     *
     * @return クラス ID 順の進行度一覧
     */
    fun getAllClassProgresses(): List<ClassProgressModel> =
        classProgressById.values.sortedBy { it.classId }

    /** 指定クラスの進行度を更新します。 */
    fun setClassProgress(targetClassId: String, level: Int, experience: Long) {
        updateClassProgress(targetClassId, level, experience)
    }

    private fun updateClassProgress(targetClassId: String, level: Int, experience: Long) {
        val normalized = normalizeClassId(targetClassId)
        val existingKey = classProgressById.keys.firstOrNull { it.equals(normalized, ignoreCase = true) }
        if (existingKey != null && existingKey != normalized) {
            classProgressById.remove(existingKey)
        }
        classProgressById[normalized] = ClassProgressModel(
            classId = normalized,
            level = level.coerceAtLeast(1),
            experience = experience.coerceAtLeast(0L),
        )
    }

    private fun normalizeClassId(value: String): String = value.trim().ifBlank { "adventurer" }

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
        return BedrockPlayerDetector.isBedrock(
            bukkit.name,
            ConfigProperties.getInstance().resourcePackBedrockNamePrefixes,
        )
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
            PlayerMessageService.getInstance().send(this, PlayerMsgId.P_5070, newUser.permission)
        } else {
            bukkit.isOp = false
        }
    }

    /**
     * 指定した権限レベル以上を保持しているか判定する。
     *
     * @param requiredPermissionLevel 要求する最小 permission 値
     * @return 現在の user.permission が要求値以上なら true
     */
    fun hasPermissionLevel(requiredPermissionLevel: Int): Boolean =
        user.permission >= requiredPermissionLevel

    /**
     * 管理者権限を保持しているか判定する。
     *
     * @return user.permission が 99 以上なら true
     */
    fun hasAdminPermission(): Boolean = hasPermissionLevel(OP_PERMISSION_THRESHOLD)

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
            if (bukkit.gameMode == GameMode.ADVENTURE) {
                GameModeChangeGuard.setGameMode(bukkit, GameMode.SURVIVAL)
            }
            applyReach(Attribute.ENTITY_INTERACTION_RANGE, 3.0)
            applyReach(Attribute.BLOCK_INTERACTION_RANGE, 4.5)
            return
        }

        GameModeChangeGuard.setGameMode(bukkit, GameMode.ADVENTURE)
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
     * 現在スキル詠唱中かを返します。
     *
     * @return 詠唱中なら true
     */
    fun isSkillCasting(): Boolean = skillCastingUntilMs > System.currentTimeMillis()

    /**
     * プレイヤーにメッセージを送信します。
     * メッセージ文言は [PlayerMsgId] 経由で [PlayerMsgResource] から取得されます。
     *
     * @param msgId メッセージID
     * @param args  フォーマット引数（省略可）
     */
    @Deprecated("Use PlayerMessageService instead.")
    fun sendMessage(msgId: PlayerMsgId, vararg args: Any) {
        PlayerMessageService.getInstance().send(this, msgId, *args)
    }
}
