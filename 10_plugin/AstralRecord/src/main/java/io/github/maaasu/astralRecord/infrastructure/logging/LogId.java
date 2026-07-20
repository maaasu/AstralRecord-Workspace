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
    I_1104(1104),
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
    /**
     * コマンド実行中に例外が発生しました: command=%s, sender=%s, argCount=%d
     */
    E_1501(1501),
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
     * マスターデータ再読込を開始しました。実行者: %s
     */
    I_1554(1554),
    /**
     * マスターデータ再読込が完了しました
     */
    I_1555(1555),
    /**
     * マスターデータ公開後の実行時再同期に失敗しました: 対象=%s
     */
    W_1550(1550),
    /**
     * リロード中にエラーが発生しました: %s
     */
    E_1550(1550),
    /**
     * マスターデータ再読込に失敗しました: %s
     */
    E_1551(1551),
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
    /**
     * ログインボーナス API の呼び出しに失敗しました: %s
     */
    E_5071(5071),
    /**
     * プレイヤー参加データ読み込みの再試行待機が中断されました: プレイヤー=%s
     */
    E_5073(5073),
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
    /**
     * プレイヤーの入力イベント処理に失敗しました: %s
     */
    E_5171(5171),
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
    I_5103(5103),
    // endregion

    // region /feature/account/repository/AccountRepository.kt
    /**
     * アカウント一覧を取得しました (API): ユーザーID=%s (%d件)
     */
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
    D_5155(5155),
    E_5155(5155),
    /**
     * 停止時にアカウント進行の未保存更新が残りました: experience=%d, class=%d
     */
    W_5156(5156),
    /**
     * アカウント経験値の保存に失敗しました: accountId=%s, level=%d, totalExperience=%d
     */
    E_5156(5156),
    /**
     * クラス進行の保存に失敗しました: accountId=%s, classId=%s, level=%d, experience=%d
     */
    E_5157(5157),
    /**
     * Mob報酬の経験値反映に失敗しました: accountId=%s, experience=%d
     */
    E_5158(5158),
    // endregion

    // region /feature/guide/ 5180-5189
    /**
     * ガイドデータ取得に失敗しました (API): operation=%s, target=%s, reason=%s
     */
    E_5180(5180),
    /**
     * ガイドキャッシュ更新に失敗し、直前値を使用します: %s
     */
    E_5181(5181),
    /**
     * ガイド進行処理に失敗しました (API): operation=%s, target=%s, reason=%s
     */
    E_5182(5182),
    // endregion

    // region /feature/mail/ 5190-5199
    /**
     * メールデータ処理に失敗しました (API): operation=%s, target=%s, reason=%s
     */
    E_5190(5190),
    // endregion

    // region /feature/item/repository/ItemRepository.kt /feature/item/service/ItemService.java
    /**
     * アイテムを使用しました: プレイヤー=%s, アイテム=%s, 適用=%d, スキップ=%d
     */
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
    /**
     * consumable 定義が存在しません: アイテム=%s
     */
    /**
     * 未対応の消耗品効果タイプを検出しました: アイテム=%s
     */
    /**
     * BUFF 効果に buffId が設定されていません: アイテム=%s
     */
    W_5204(5204),
    /**
     * STATUS 効果に status が設定されていません: アイテム=%s
     */
    /**
     * STATUS 効果の status が不正です: アイテム=%s, ステータス=%s
     */
    /**
     * STATUS 効果の対象が未対応です: アイテム=%s, ステータス=%s
     */
    /**
     * 未対応カテゴリを指定しました (API): カテゴリ=%s
     */
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
     * インベントリ状態が未登録のため即時保存を開始できません: accountId=%s
     */
    W_5254(5254),
    /**
     * インベントリ即時保存後も未保存変更が残っています: accountId=%s
     */
    W_5255(5255),
    /**
     * ログアウト保存後も未保存変更が残っています: accountId=%s
     */
    W_5256(5256),
    /**
     * プラグイン停止前のインベントリ保存待機がタイムアウトしました: accountId=%s
     */
    W_5257(5257),
    /**
     * 装備プリセットスロット同期に失敗しました: loadoutId=%s, slotType=%s, slotIndex=%d, reason=%s
     */
    /**
     * BUFF 効果を適用しました: アイテム=%s, buffId=%s, 有効数=%d
     */
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
    /**
     * インベントリ補償に失敗しました: operation=%s, accountId=%s
     */
    W_5203(5203),
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

    // region /feature/playersetting 5310-5319
    W_5310(5310),
    W_5311(5311),
    W_5312(5312),
    E_5310(5310),
    E_5311(5311),
    E_5312(5312),
    E_5313(5313),
    E_5314(5314),
    // endregion

    // region /feature/item/repository/SetEffectRepository.kt 5400-5409
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
     * GUI イベント処理に失敗しました: player=%s, operation=%s
     */
    E_5601(5601),
    /**
     * ショートカット設定の取得に失敗しました（デフォルト設定を使用）: %s
     */
    /**
     * ショートカット設定の保存に失敗しました（デフォルト設定に初期化）: %s
     */
    // endregion

    // region /feature/mob/ 5700-5799
    /**
     * 全 Mob テンプレートを一括ロードしました: %d件
     */
    I_5700(5700),
    /**
     * Mob を取得しました (API): %s
     */
    D_5700(5700),
    /**
     * Mob が見つかりませんでした (API): %s
     */
    W_5700(5700),
    /**
     * Mob 取得に失敗しました (API): %s
     */
    E_5700(5700),
    /**
     * Mob 一覧取得に失敗しました (API): %s
     */
    E_5701(5701),
    /**
     * Mob をスポーンしました: テンプレート=%s, インスタンス=%s
     */
    D_5701(5701),
    /**
     * Mob テンプレートが見つかりません: %s
     */
    W_5701(5701),
    /**
     * Mob を破棄しました: インスタンス=%s
     */
    D_5702(5702),
    /**
     * 全 Mob を破棄しました: %d件
     */
    I_5701(5701),
    /**
     * Mob AI tick で例外が発生しました: インスタンス=%s
     */
    W_5702(5702),
    /**
     * Mob AI tick 全体で例外が発生しました
     */
    E_5702(5702),
    /**
     * Mob 致死判定: mobId=%s, killer=%s
     */
    D_5703(5703),
    /**
     * Mob キラー特定失敗: mobId=%s
     */
    W_5703(5703),
    /**
     * Mob ドロップ抽選で例外が発生しました: mobId=%s
     */
    E_5703(5703),
    /**
     * Mob ベースステータスを解決できませんでした: status=%s, mobId=%s
     */
    W_5704(5704),
    /**
     * Mob entityType を解決できませんでした: entityType=%s, mobId=%s
     */
    W_5705(5705),
    /**
     * Mob パケット送出に失敗しました: %s
     */
    /**
     * Mob AI tick タスクを開始しました
     */
    I_5702(5702),
    /**
     * Mob AI tick タスクを停止しました
     */
    I_5703(5703),
    // endregion

    // region /feature/world/ 5750-5799
    I_5750(5750),
    I_5751(5751),
    I_5753(5753),
    I_5754(5754),
    D_5750(5750),
    D_5751(5751),
    W_5750(5750),
    W_5751(5751),
    W_5752(5752),
    W_5753(5753),
    W_5754(5754),
    W_5755(5755),
    E_5750(5750),
    E_5751(5751),
    E_5752(5752),
    E_5753(5753),
    E_5754(5754),
    E_5755(5755),
    E_5756(5756),
    // endregion

    // region /feature/skill/ 5800-5899
    /**
     * スキル詳細を取得しました (API): %s
     */
    D_5800(5800),
    /**
     * スキルが見つかりませんでした (API): %s
     */
    W_5800(5800),
    /**
     * スキル取得に失敗しました (API): %s
     */
    E_5800(5800),
    /**
     * スキル一覧取得に失敗しました (API): %s
     */
    E_5801(5801),
    /**
     * スキル定義を読み込みました: count=%s
     */
    I_5800(5800),
    /**
     * スキル定義を読み込みませんでした: skillId=%s, implementationId=%s, reason=%s
     */
    W_5801(5801),
    /**
     * 実行クラスが既に登録されています（後勝ち禁止）: implementationId=%s
     */
    W_5802(5802),
    /**
     * スキル定義をレジストリに反映しました: count=%d
     */
    D_5801(5801),
    /**
     * スキル発動に失敗しました: skillId=%s, implementationId=%s
     */
    E_5802(5802),
    /**
     * スキルバインドプリセット読込に失敗しました: %s
     */
    E_5803(5803),
    /**
     * スキルバインドプリセット保存に失敗しました: %s
     */
    E_5804(5804),
    // endregion

    // region /feature/combat/ 5900-5999
    /**
     * 戦闘ダメージ処理に失敗しました: %s
     */
    E_5900(5900),
    /**
     * 状態異常 tick 処理に失敗しました: condition=%s target=%s
     */
    E_5901(5901),
    I_5950(5950),
    E_5950(5950),
    E_5951(5951),
    E_5952(5952),
    E_5953(5953),
    E_5954(5954),
    /** プレイヤー入力調停に失敗しました: player=%s, source=%s */
    E_5999(5999),
    // endregion

    // region /feature/equipment/event/EquipmentAttackEventHandler.java 6000-6099
    /**
     * ホットバーアクション処理に失敗しました: %s
     */
    E_6000(6000),
    // endregion

    // region /feature/party/ 6100-6199
    W_6100(6100),
    E_6100(6100),
    // endregion

    // region /feature/trade/ 6200-6299
    E_6200(6200),
    E_6201(6201),
    W_6202(6202),
    // endregion

    // region /feature/shop/ 6300-6399
    /**
     * ショップ購入失敗を補償しました: player=%s, shopId=%s, itemId=%s, amount=%d, reason=%s
     */
    W_6300(6300),
    // endregion

    // region shared local placement persistence 6400-6499
    /**
     * ローカル配置データの保存に失敗しました: type=%s, path=%s, reason=%s
     */
    E_6400(6400),
    /**
     * ローカル配置データの保存先ディレクトリを作成できません: type=%s, path=%s
     */
    E_6401(6401),
    // endregion

    // region /feature/boss/ 6500-6529
    I_6500(6500),
    I_6501(6501),
    I_6502(6502),
    W_6501(6501),
    W_6502(6502),
    E_6500(6500),
    E_6501(6501),
    E_6502(6502),
    E_6503(6503),
    // endregion

    // region /feature/quest/ 6600-6699
    /**
     * クエスト状態の保存に失敗しました: accountId=%s, reason=%s
     */
    W_6600(6600),
    /**
     * クエスト報酬の準備に失敗しました: accountId=%s, questId=%s, reason=%s
     */
    W_6601(6601),
    /**
     * クエスト状態の保存先ディレクトリを作成できません: accountId=%s, path=%s
     */
    W_6602(6602),
    /**
     * クエスト状態ファイルを保存できません: accountId=%s, path=%s
     */
    W_6603(6603),
    /**
     * クエスト報酬の反映に失敗したため補償しました: accountId=%s, questId=%s
     */
    W_6604(6604),
    /**
     * クエスト完了演出の実行に失敗しました: accountId=%s, questId=%s
     */
    W_6605(6605),
    /**
     * クエスト報酬の補償処理に失敗しました: accountId=%s, questId=%s
     */
    W_6606(6606),
    // endregion

    // region /feature/skilltree/ 9000-9009
    I_9000(9000),
    I_9001(9001),
    I_9002(9002),
    I_9003(9003),
    W_9000(9000),
    W_9002(9002),
    W_9003(9003),
    E_9004(9004),
    // endregion

    // region /feature/gathering/ 9010-9019
    W_9010(9010),
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
