package io.github.maaasu.astralRecord.infrastructure.logging;

/**
 * ログIDを定義するenum。
 * メッセージID形式: {Type}_{Number}
 * {Type}
 * - I: Info
 * - W: Warning
 * - E: Error
 * - D: Debug
 */
public enum LogId {

    // ==================== plugin lifecycle ====================

    // region /AstralRecord.java
    /**
     * 基盤セットアップに失敗しました
     */
    E_900(900),
    // endregion

    // ==================== infrastructure ====================

    // region /util/YamlLoaderUtil.java
    /**
     * 指定されたYAMLファイルが見つかりませんでした: %s
     */
    W_1000(1000),
    /**
     * YAMLファイルの読み込みに失敗しました: %s
     */
    E_1000(1000),
    /**
     * YAMLファイルが正常に読み込まれました: %s
     */
    D_1000(1000),
    /**
     * ディレクトリが存在しないか、読み取り権限がありません: %s
     */
    E_1001(1001),
    /**
     * YAMLファイルのセーブに失敗しました: %s
     */
    E_1002(1002),
    // endregion

    // region /infrastructure/logging/AuditLoggerRegistry.java
    /**
     * AuditLogger を初期化しました: %s
     */
    D_2100(2100),
    /**
     * AuditLogger の初期化をスキップしました（メタ情報またはデフォルトコンストラクタなし）: %s
     */
    W_2100(2100),
    /**
     * AuditLogger エントリのインスタンス生成に失敗しました: クラス=%s, 理由=%s
     */
    W_2101(2101),
    // endregion

    // ==================== core ====================

    // region /core/event/AbstractEventHandler.java
    /**
     * イベントハンドラーが初期化されました: %s
     */
    I_3000(3000),
    /**
     * イベントハンドラーのクリーンアップを実行しました: %s
     */
    I_3001(3001),
    // endregion

    // region /core/event/EventManager.java
    /**
     * イベントマネージャーを初期化しています
     */
    I_3050(3050),
    /**
     * イベントハンドラーを登録しました。登録数: %d
     */
    I_3051(3051),
    /**
     * イベントマネージャーをシャットダウンしています
     */
    I_3052(3052),
    /**
     * イベントマネージャーのシャットダウンが完了しました
     */
    I_3053(3053),
    /**
     * イベントハンドラーが無効のためスキップしました: %s
     */
    W_3000(3000),
    /**
     * イベントハンドラーの登録に失敗しました: %s
     */
    E_3000(3000),
    /**
     * イベントハンドラーのクリーンアップに失敗しました: %s
     */
    E_3001(3001),
    /**
     * イベントハンドラーを登録しました: %s
     */
    D_3000(3000),
    /**
     * イベントハンドラーが無効のため登録をスキップしました: %s
     */
    D_3001(3001),
    // endregion

    // region /database/sqlserver/SqlServerManager.java
    /**
     * SQL Serverへの接続を初期化しています
     */
    I_1100(1100),
    /**
     * SQL Serverへの接続テストに成功しました
     */
    I_1101(1101),
    /**
     * SQL Serverが正常に初期化されました
     */
    I_1102(1102),
    /**
     * SQL Serverが正常にシャットダウンされました
     */
    I_1103(1103),
    /**
     * SQL Serverの接続テストに失敗しました: %s
     */
    E_1100(1100),
    /**
     * SQL Serverが初期化されていません
     */
    E_1101(1101),
    // endregion

    // region /database/file/yaml/config/YamlDbConfigUtil.kt
    /**
     * FileDatabaseのルートディレクトリの取得に失敗しました
     */
    W_1400(1400),
    /**
     * YAMLデータベースの設定ファイルが見つかりません: %s
     */
    W_1401(1401),
    /**
     * YAMLデータベースの設定がロードされました
     */
    I_1400(1400),
    /**
     * YAMLデータベースの設定のパースに失敗しました: %s
     */
    E_1400(1400),
    // endregion

    // region /infrastructure/command/CommandManager.java
    /**
     * CommandManagerはすでに初期化されています
     */
    W_1500(1500),
    /**
     * 初期化後にコマンドを登録しようとしました: %s
     */
    W_1501(1501),
    /**
     * CommandManagerを初期化しています
     */
    I_1500(1500),
    /**
     * CommandManagerが初期化されました。登録コマンド数: %d
     */
    I_1501(1501),
    /**
     * CommandManagerをシャットダウンしています
     */
    I_1502(1502),
    /**
     * CommandManagerのシャットダウンが完了しました
     */
    I_1503(1503),
    /**
     * コマンドを登録しました: %s
     */
    D_1500(1500),
    /**
     * コマンドをBrigadierに登録しました: %s
     */
    D_1501(1501),
    /**
     * コマンドの登録中にエラーが発生しました: %s
     */
    E_1500(1500),
    // endregion

    // region /infrastructure/command/ReloadCommand.java
    /**
     * リロードコマンドを実行しました。実行者: %s
     */
    I_1550(1550),
    /**
     * PlugMan を検出しました。PlugMan でリロードを実行します
     */
    I_1551(1551),
    /**
     * PlugMan が見つかりません。内部リロードを実行します
     */
    I_1552(1552),
    /**
     * 内部リロードが完了しました
     */
    I_1553(1553),
    /**
     * リロード中にエラーが発生しました: %s
     */
    E_1550(1550),
    // endregion

    // region /infrastructure/api/ApiHealthChecker.java
    /**
     * AstralRecord API の疎通確認を開始します: %s
     */
    I_1600(1600),
    /**
     * AstralRecord API の疎通確認に成功しました (HTTP %d)
     */
    I_1601(1601),
    /**
     * AstralRecord API の疎通確認でエラーレスポンスが返されました (HTTP %d)
     */
    W_1600(1600),
    /**
     * AstralRecord API の SSL 証明書検証が無効です。本番環境では有効化してください
     */
    W_1601(1601),
    /**
     * AstralRecord API の疎通確認に失敗しました: %s
     */
    E_1600(1600),
    // endregion

    // ==================== feature ====================

    // region /feature/user/event/UserLoginEventHandler.java
    /**
     * ユーザーのログイン前処理に失敗しました: %s
     */
    E_5000(5000),
    // endregion

    // region /feature/user/repository/UserRepository.kt
    /**
     * ユーザーを取得しました (API): %s
     */
    D_5055(5055),
    /**
     * ユーザーが見つかりませんでした (API): %s
     */
    W_5055(5055),
    /**
     * ユーザーの取得に失敗しました (API): %s
     */
    E_5055(5055),
    /**
     * ユーザーを登録しました (API): %s
     */
    D_5056(5056),
    /**
     * ユーザーの登録に失敗しました (API): %s
     */
    E_5056(5056),
    /**
     * アカウントIDを更新しました (API): %s → %s
     */
    D_5057(5057),
    /**
     * アカウントIDの更新に失敗しました (API): %s
     */
    E_5057(5057),
    /**
     * ログイン情報を更新しました (API): %s
     */
    D_5058(5058),
    /**
     * ログイン情報の更新に失敗しました (API): %s
     */
    E_5058(5058),
    /**
     * permissionを更新しました (API): %s → %s
     */
    D_5059(5059),
    /**
     * permissionの更新に失敗しました (API): %s
     */
    E_5059(5059),
    /**
     * ユーザー履歴を登録しました (API): user=%s, eventType=%s
     */
    D_5060(5060),
    /**
     * ユーザー履歴の登録に失敗しました (API): %s
     */
    E_5060(5060),
    // endregion

    // region /feature/user/service/UserService.java
    /**
     * 新規ユーザーを登録しました: %s (%s)
     */
    I_5050(5050),
    /**
     * ログイン前処理をスキップしました（API一時障害）: %s (%s)
     */
    W_5051(5051),
    /**
     * ユーザー取得をスキップしました（API一時障害）: %s (%s)
     */
    W_5052(5052),
    // endregion

    // region /feature/user/command/UserPermissionCommand.java
    /**
     * permission を変更しました: 実行者=%s, 対象=%s, 変更前=%s, 変更後=%s, 経路=%s
     */
    I_5053(5053),
    // endregion

    // region /feature/player/event/PlayerJoinEventHandler.java
    /**
     * プレイヤーにOP権限を付与しました: %s (permission=%d)
     */
    I_5070(5070),
    /**
     * AstPlayerのキャッシュが見つかりませんでした。プレイヤー: %s
     */
    W_5070(5070),
    /**
     * プレイヤーデータ保存に失敗しました: タスク=%s, 契機=%s, プレイヤー=%s, 理由=%s
     */
    W_5071(5071),
    /**
     * プレイヤーログイン処理に失敗しました: %s
     */
    E_5070(5070),
    // endregion

    // region /feature/player/event/PlayerModeEventHandler.java
    /**
     * プレイヤーモード操作制限処理に失敗しました: %s
     */
    E_5072(5072),
    // endregion

    // region /feature/player/event/PlayerSneakEventHandler.java
    /**
     * プレイヤーのしゃがみイベント処理に失敗しました: %s
     */
    E_5170(5170),
    // endregion

    // region /feature/account/service/AccountService.java
    /**
     * アカウントを作成しました: %s (slot=%d, user=%s)
     */
    I_5100(5100),
    /**
     * アカウントを切り替えました: %s (user=%s)
     */
    I_5101(5101),
    /**
     * アカウントモードを更新しました: %s (mode=%s, updatedBy=%s)
     */
    I_5102(5102),
    // endregion

    // region /feature/account/repository/AccountRepository.kt
    /**
     * アカウント一覧を取得しました (API): ユーザーID=%s (%d件)
     */
    D_5150(5150),
    /**
     * アカウントが見つかりませんでした (API): ユーザーID=%s
     */
    W_5150(5150),
    /**
     * アカウント一覧の取得に失敗しました (API): %s
     */
    E_5150(5150),
    /**
     * アカウントを取得しました (API): %s
     */
    D_5151(5151),
    /**
     * アカウントが見つかりませんでした (API): %s
     */
    W_5151(5151),
    /**
     * アカウントの取得に失敗しました (API): %s
     */
    E_5151(5151),
    /**
     * アカウントを登録しました (API): %s
     */
    D_5152(5152),
    /**
     * アカウントの登録に失敗しました (API): %s
     */
    E_5152(5152),
    /**
     * アクティブアカウントを切り替えました (API): userId=%s → accountId=%s
     */
    D_5153(5153),
    /**
     * アクティブアカウントの切り替えに失敗しました (API): %s
     */
    E_5153(5153),
    /**
     * アカウントモードを更新しました (API): accountId=%s, mode=%s
     */
    D_5154(5154),
    /**
     * アカウントモードの更新に失敗しました (API): %s
     */
    E_5154(5154),
    // endregion

    // region /feature/item/repository/ItemRepository.kt /feature/item/service/ItemService.java
    /**
     * アイテムを使用しました: プレイヤー=%s, アイテム=%s, 適用=%d, スキップ=%d
     */
    I_5200(5200),
    /**
     * カテゴリのアイテムを一括ロードしました (API): カテゴリ=%s (%d件)
     */
    I_5202(5202),
    /**
     * 全カテゴリのアイテムを初期ロードしました: 合計 %d件
     */
    I_5203(5203),
    /**
     * アイテムが見つかりませんでした (API): カテゴリ=%s, ID=%s
     */
    W_5200(5200),
    /**
     * 消耗品ではないアイテムを使用しようとしました: アイテム=%s, カテゴリ=%s
     */
    W_5201(5201),
    /**
     * consumable 定義が存在しません: アイテム=%s
     */
    W_5202(5202),
    /**
     * 未対応の消耗品効果タイプを検出しました: アイテム=%s
     */
    W_5203(5203),
    /**
     * BUFF 効果に buffId が設定されていません: アイテム=%s
     */
    W_5204(5204),
    /**
     * STATUS 効果に status が設定されていません: アイテム=%s
     */
    W_5205(5205),
    /**
     * STATUS 効果の status が不正です: アイテム=%s, ステータス=%s
     */
    W_5206(5206),
    /**
     * STATUS 効果の対象が未対応です: アイテム=%s, ステータス=%s
     */
    W_5207(5207),
    /**
     * 未対応カテゴリを指定しました (API): カテゴリ=%s
     */
    W_5208(5208),
    /**
     * インベントリスナップショットのエンコードに失敗しました: %s
     */
    W_5250(5250),
    /**
     * インベントリスナップショットのデコードに失敗しました: %s
     */
    W_5251(5251),
    /**
     * インベントリ同期に失敗しました: target=%s, reason=%s
     */
    W_5252(5252),
    /**
     * 装備プリセットの初期化に失敗しました。従来のインベントリ保存のみ継続します: accountId=%s, reason=%s
     */
    W_5253(5253),
    /**
     * 装備プリセットスロット同期に失敗しました: loadoutId=%s, slotType=%s, slotIndex=%d, reason=%s
     */
    W_5254(5254),
    /**
     * BUFF 効果を適用しました: アイテム=%s, buffId=%s, 有効数=%d
     */
    D_5201(5201),
    /**
     * カテゴリのアイテム一覧を取得しました (API): カテゴリ=%s (%d件)
     */
    D_5202(5202),
    /**
     * アイテム詳細をロードしました: %s
     */
    D_5203(5203),
    /**
     * アイテム取得に失敗しました (API): %s
     */
    E_5200(5200),
    /**
     * カテゴリのアイテム一括ロードに失敗しました: category=%s
     */
    E_5201(5201),
    /**
     * カテゴリのアイテム一括ロードに失敗しました (Service): category=%s
     */
    E_5202(5202),
    // endregion

    // region /feature/item/service/ItemStackFactory.java /feature/item/view/ItemStackPacketAdapter.java 5210-5219
    /**
     * ItemStackPacketAdapter を登録しました
     */
    I_5210(5210),
    /**
     * 不明な icon Material 名です: %s
     */
    W_5210(5210),
    /**
     * ItemStack テンプレートキャッシュをクリアしました
     */
    D_5210(5210),
    /**
     * ItemStack テンプレートを構築しました: カテゴリ=%s, ID=%s
     */
    D_5211(5211),
    // endregion

    // region /feature/loot/repository/LootRepository.kt /feature/loot/service/LootService.java 5300-5319
    /**
     * ルートテーブルを取得しました (API): id=%s
     */
    D_5300(5300),
    /**
     * ルートテーブルが見つかりませんでした (API): id=%s
     */
    W_5300(5300),
    /**
     * 全ルートテーブルをロードしました: %d件
     */
    I_5300(5300),
    /**
     * ルートテーブル取得に失敗しました (API): %s
     */
    E_5300(5300),
    /**
     * ルートテーブル詳細をロードしました: %s
     */
    D_5301(5301),
    /**
     * ルートテーブル一括ロードに失敗しました (Service): %s
     */
    E_5301(5301),
    // endregion

    // region /feature/item/repository/SetEffectRepository.kt 5400-5409
    /**
     * セット効果を取得しました (API): id=%s
     */
    D_5400(5400),
    /**
     * セット効果が見つかりませんでした (API): id=%s
     */
    W_5400(5400),
    /**
     * セット効果取得に失敗しました (API): %s
     */
    E_5400(5400),
    /**
     * セット効果一覧取得に失敗しました (API): %s
     */
    E_5401(5401),
    // endregion

    // region /feature/buff/repository/BuffRepository.kt /feature/buff/service/BuffService.java 5450-5499
    /**
     * バフ詳細をロードしました: %s
     */
    D_5451(5451),
    // endregion

    // region /feature/class/repository/ClassRepository.kt 5500-5509
    /**
     * 全クラスを一括ロードしました: %d件
     */
    I_5500(5500),
    /**
     * クラスを取得しました (API): %s
     */
    D_5500(5500),
    /**
     * クラスが見つかりませんでした (API): %s
     */
    W_5500(5500),
    /**
     * クラス取得に失敗しました (API): %s
     */
    E_5500(5500),
    /**
     * クラス一覧取得に失敗しました (API): %s
     */
    E_5501(5501),
    /**
     * クラス機能の処理に失敗しました: %s
     */
    E_5502(5502),
    // endregion

    // region /feature/resourcepack/service/ResourcePackService.java 5550-5599
    /**
     * リソースパックの SHA-1 が不正です: URL=%s, SHA-1=%s
     */
    W_5550(5550),
    /**
     * リソースパック要求を送信しました: プレイヤー=%s, URL=%s, 強制=%s
     */
    I_5550(5550),
    /**
     * Bedrock プレイヤーのため Java リソースパック要求をスキップしました: プレイヤー=%s
     */
    I_5551(5551),
    /**
     * プレイヤーがリソースパックを承諾しました: プレイヤー=%s
     */
    I_5552(5552),
    /**
     * プレイヤーがリソースパックをダウンロードしました: プレイヤー=%s
     */
    I_5553(5553),
    /**
     * プレイヤーがリソースパックを正常に読み込みました: プレイヤー=%s
     */
    I_5554(5554),
    /**
     * プレイヤーがリソースパック適用を破棄しました: プレイヤー=%s
     */
    W_5551(5551),
    /**
     * プレイヤーがリソースパックを拒否しました: プレイヤー=%s
     */
    W_5552(5552),
    /**
     * 未処理のリソースパックステータスです: プレイヤー=%s, ステータス=%s
     */
    D_5550(5550),
    /**
     * リソースパック処理に失敗しました: プレイヤー=%s
     */
    E_5550(5550),
    // endregion

    // region /feature/menu/event/MenuOpenEventHandler.java 5600-5609
    /**
     * メニューイベント処理に失敗しました: %s
     */
    E_5600(5600),
    /**
     * ショートカット設定の取得に失敗しました（デフォルト設定を使用）: %s
     */
    W_5601(5601),
    /**
     * ショートカット設定の保存に失敗しました（デフォルト設定に初期化）: %s
     */
    W_5602(5602),
    // endregion

    ;
    private final String id;

    /**
     * コンストラクタ。Enum名から接頭辞（最初のアンダースコアまで）を抽出し、番号と結合します。
     * @param number メッセージIDの番号部分
     */
    LogId(int number) {
        String name = this.name();
        String prefix = name.substring(0, name.indexOf('_') + 1);
        this.id = prefix + number;
    }

    /**
     * IDを取得します。
     * @return メッセージID
     */
    public String getId() {
        return id;
    }
}
