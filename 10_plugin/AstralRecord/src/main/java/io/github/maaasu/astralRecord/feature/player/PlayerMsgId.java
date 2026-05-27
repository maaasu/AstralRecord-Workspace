package io.github.maaasu.astralRecord.feature.player;

/**
 * プレイヤー向けメッセージIDを定義する enum。
 * メッセージID形式: {Type}_{Number}
 * <p>
 * {Type}
 * - P: Player（プレイヤー向け汎用メッセージ）
 * <p>
 * メッセージ文言は {@code player.properties} で管理します。
 * <p>
 * 使用例:
 * <pre>
 * // パラメータあり
 * AstPlayer.sendMessage(PlayerMsgId.P_5000, player.getName());
 * </pre>
 */
public enum PlayerMsgId {

    // ==================== feature ====================

    // region /feature/player/model/AstPlayer.kt 5000-5049
    /**
     * AstralRecord へようこそ、%s さん！
     */
    P_5000(5000),
    // endregion

    // region /temp/command/TestCommand.java 5050-5059
    /**
     * [TEST] プレイヤー情報: 名前={0}, UUID={1}
     */
    P_5050(5050),
    /**
     * [TEST] ユーザー権限レベル: {0}
     */
    P_5051(5051),
    // endregion

    // region /temp/command/TempCommand.java 5080-5089
    /**
     * [TEMP] テンポラリコマンドを実行しました。実行者: {0}
     */
    P_5080(5080),
    // endregion

    // region /feature/player/event/PlayerJoinEventHandler.java 5070-5079
    /**
     * あなたはpermissionレベル{0}の管理者です。OP権限が付与されました。
     */
    P_5070(5070),
    // endregion

    // region /infrastructure/command/AstCommand.java 5060-5069
    /**
     * このコマンドはプレイヤーのみ使用できます。
     */
    P_5060(5060),
    /**
     * このコマンドを実行する権限がありません。
     */
    P_5061(5061),
    /**
     * コマンドの実行中にエラーが発生しました: {0}
     */
    P_5062(5062),
    /**
     * 引数が不足しています。
     */
    P_5063(5063),
    /**
     * 使用方法: {0}
     */
    P_5064(5064),
    // endregion

    // region /infrastructure/command/ReloadCommand.java 5090-5099
    /**
     * リロードを開始します...
     */
    P_5090(5090),
    /**
     * PlugMan を使用してリロードします
     */
    P_5091(5091),
    /**
     * PlugMan が見つかりません。内部リロードを実行します
     */
    P_5092(5092),
    /**
     * 内部リロードが完了しました（設定・YAMLデータ）
     */
    P_5093(5093),
    /**
     * リロード中にエラーが発生しました: {0}
     */
    P_5094(5094),
    // endregion

    // region /feature/status/command/StatusCommand.java 5100-5149
    /**
     * ステータス: {0}
     */
    P_5100(5100),
    /**
     * {0}: {1}
     */
    P_5101(5101),
    /**
     * ステータスを再計算しました。
     */
    P_5102(5102),
    /**
     * {0}: 基礎 {1} / 補正 {2} / 合計 {3}
     */
    P_5103(5103),
    /**
     * 最終更新: {0}
     */
    P_5104(5104),
    /**
     * 現在HP/MP: {0}/{1} | {2}/{3}
     */
    P_5105(5105),
    /**
     * 現在EN: {0}/{1}
     */
    P_5106(5106),
    /**
     * セット効果:
     */
    P_5107(5107),
    /**
     * {0}: 装備数 {1} / 発動 {2}
     */
    P_5108(5108),
    // endregion

    // region /feature/item/command/ItemCommand.java 5200-5249
    /**
     * 無効なカテゴリです: {0}
     */
    P_5200(5200),
    /**
     * アイテムが見つかりません: category={0}, id={1}
     */
    P_5201(5201),
    /**
     * アイテム: {0} ({1})
     */
    P_5202(5202),
    /**
     * カテゴリ: {0} / レアリティ: {1} / 売却値: {2}
     */
    P_5203(5203),
    /**
     * 説明はありません。
     */
    P_5204(5204),
    /**
     * 説明:
     */
    P_5205(5205),
    /**
     * - {0}
     */
    P_5206(5206),
    /**
     * {0} は消耗品ではないため使用できません。
     */
    P_5207(5207),
    /**
     * {0} を使用しました。適用: {1}件 / スキップ: {2}件
     */
    P_5208(5208),
    /**
     * ロードしました: {0}/{1} ({2})
     */
    P_5209(5209),
    /**
     * ロード済みアイテムはありません。
     */
    P_5210(5210),
    /**
     * ロード済みアイテム一覧 ({0}件)
     */
    P_5211(5211),
    /**
     * [{0}] {1} - {2}
     */
    P_5212(5212),
    /**
     * 指定IDのロード済みアイテムが見つかりません: {0}
     */
    P_5213(5213),
    /**
     * 同一IDが複数カテゴリに存在します。カテゴリを指定してください: {0}
     */
    P_5214(5214),
    /**
     * 候補: [{0}] {1} - {2}
     */
    P_5215(5215),
    /**
     * icon={0}, schema={1}, customModelData={2}
     */
    P_5216(5216),
    /**
     * 取引不可={0}, 売却不可={1}
     */
    P_5217(5217),
    /**
     * maxStack={0}
     */
    P_5218(5218),
    /**
     * [bundle]
     */
    P_5219(5219),
    /**
     * lootTableId={0}
     */
    P_5220(5220),
    /**
     * onUse: sound={0}, effect={1}, particle={2}
     */
    P_5221(5221),
    /**
     * [currency]
     */
    P_5222(5222),
    /**
     * type={0}, group={1}, expiresAt={2}
     */
    P_5223(5223),
    /**
     * [equipment]
     */
    P_5224(5224),
    /**
     * slot={0}, handType={1}, requiredLevel={2}
     */
    P_5225(5225),
    /**
     * requiredClasses={0}
     */
    P_5226(5226),
    /**
     * stat: {0} {1} {2}
     */
    P_5227(5227),
    /**
     * durability: max={0}, consume={1}
     */
    P_5228(5228),
    /**
     * onUse: leftCooldown={0}, leftSkill={1}, rightCooldown={2}, rightSkill={3}
     */
    P_5229(5229),
    /**
     * skills={0}
     */
    P_5230(5230),
    /**
     * [{0}] 追加セクションはありません。
     */
    P_5231(5231),
    /**
     * [{0}] 未対応カテゴリです。
     */
    P_5232(5232),
    /**
     * [{0}] セクション定義がありません。
     */
    P_5233(5233),
    /**
     * [consumable]
     */
    P_5234(5234),
    /**
     * onUse: sound={0}, effect={1}, amount={2}
     */
    P_5235(5235),
    /**
     * effects={0}
     */
    P_5236(5236),
    /**
     * effect: type={0}, rate={1}, status={2}, value={3}, isPercent={4}, buffId={5}
     */
    P_5237(5237),
    /**
     * [lore]
     */
    P_5238(5238),
    /**
     * アイテムを付与しました: {0} x{1}
     */
    P_5240(5240),
    /**
     * インベントリに空きがありません。
     */
    P_5241(5241),
    // endregion

    // region /feature/user/command/UserPermissionCommand.java 5300-5309
    /**
     * 対象プレイヤーまたはUUIDが見つかりません: {0}
     */
    P_5300(5300),
    /**
     * permission は99以下の数値で指定してください。
     */
    P_5301(5301),
    /**
     * UserService が初期化されていません。
     */
    P_5302(5302),
    /**
     * ユーザー更新後の取得に失敗しました: {0}
     */
    P_5303(5303),
    /**
     * permission を更新しました: {0} = {1}
     */
    P_5304(5304),
    /**
     * このコマンドをコンソールから実行する場合は対象プレイヤーの指定が必要です。
     */
    P_5305(5305),
    // endregion

    // region /feature/playersetting 5320-5329
    P_5320(5320),
    P_5321(5321),
    P_5322(5322),
    P_5323(5323),
    P_5324(5324),
    // endregion

    // region /feature/inventory/command/InventoryCommand.java 5250-5269
    /**
     * 現在の mode では AstralRecord インベントリを反映しません。
     */
    P_5250(5250),
    /**
     * 無効なインベントリタイプです。指定可能: {0}
     */
    P_5251(5251),
    /**
     * InventoryService が初期化されていません。
     */
    P_5252(5252),
    /**
     * インベントリを切り替えました: {0}
     */
    P_5253(5253),
    // endregion

    // region /feature/resourcepack/service/ResourcePackService.java 5550-5599
    /**
     * リソースパックのダウンロードに失敗しました。
     */
    P_5550(5550),
    /**
     * リソースパックのURLが不正です。
     */
    P_5551(5551),
    /**
     * クライアント側でリソースパックの再読み込みに失敗しました。
     */
    P_5552(5552),
    /**
     * リソースパックが拒否されました。
     */
    P_5553(5553),
    // endregion

    // region /feature/menu 5600-5609
    /**
     * ショートカット設定の保存に失敗しました。設定を初期化しました。
     */
    P_5600(5600),
    // endregion

    // region /feature/skill 5800-5809
    /**
     * 必要レベルが不足しています。
     */
    P_5800(5800),
    /**
     * MP が不足しています。
     */
    P_5801(5801),
    /**
     * クールダウン中です。
     */
    P_5802(5802),
    /**
     * 未定義のスキルです: {0}
     */
    P_5803(5803),
    /**
     * 実行クラスが登録されていません: {0}
     */
    P_5804(5804),
    /**
     * スキル発動に失敗しました。
     */
    P_5805(5805),
    // endregion

    ;

    private final String id;

    /**
     * コンストラクタ。Enum名から接頭辞（最初のアンダースコアまで）を抽出し、番号と結合します。
     *
     * @param number メッセージIDの番号部分
     */
    PlayerMsgId(int number) {
        String name = this.name();
        String prefix = name.substring(0, name.indexOf('_') + 1);
        this.id = prefix + number;
    }

    /**
     * IDを取得します。
     *
     * @return メッセージID
     */
    public String getId() {
        return id;
    }
}

