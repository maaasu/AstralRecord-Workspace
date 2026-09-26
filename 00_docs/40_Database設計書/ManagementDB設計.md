# ManagementDB設計

## 目的と境界

`ManagementDB` はプレイヤー識別・運営上の権限などを半永久的に保持する管理DB。Webサイトやゲーム内キャラクターの寿命から独立し、通常運用でDBリセットを行わない。

- 現在の実装: `dbo.player`（UUID単位のプレイヤー情報とWeb管理フラグ）、`dbo.schema_migration`（適用済み移行台帳）、`dbo.network_settings`（ネットワーク設定）、`dbo.network_ban`（ユーザーBAN）、`dbo.network_management_audit`（設定・BAN更新監査）、`dbo.web_trusted_browser`（信頼済みブラウザの失効可能トークン）。
- ゲームデータの正本は引き続き`AstralRecord`。レベル・所持品・キャラクターIDはManagementDBへ複製しない。
- Web固定ログインID・パスワードハッシュ・Cookie失効版・ID単位の失敗試行回数はManagementDBに保持し、ゲームDBのリセット後も残す。
- ゲームDB・MasterDataDB・HistoryDBのリセット、再構築、アカウント削除からManagementDBを除外する。全DB列挙や名前prefixによる一括削除へ含めない。
- 更新はデータを保持する個別migrationで行う。DBごとのバックアップと復元手順を管理し、ゲームDB復元に連動して古いManagementDBへ戻さない。
- 特別なデータ訂正・削除が必要な場合は個別に対象と理由を確認する。通常の運営操作に全消去を設けない。

## 寄付履歴と累計配布

寄付履歴は`donation_request`、承認済み円累計は`donation_ledger`へ保存する。累計は承認遷移と同一トランザクションで更新し、ゲームDBへ外部キーを張らない。

- 各寄付を固有ID、プレイヤーUUID、申告/承認金額（円）、受付・確定日時、暗号化した支払い明細で記録する。
- 関連付けは`ManagementDB.dbo.player.player_uuid`とする。キャラクターID、ゲームDBのuser行、MCIDには依存させない。
- 同じ決済通知の再送で二重計上しない一意性を設ける。返金・取消は元履歴を消す代わりに関連記録として追跡可能にする。
- 累計は承認額だけを円単位で加算する。返金操作は今回の実装範囲外。
- ゲームデータが削除・リセットされた状態でも、UUIDで過去の寄付履歴と累計を参照できるようにする。

`donation_entry_fingerprint`は未完了・承認済み明細の再利用を拒否する。否認は受領前のみで累計に加算しない。取消・否認後は明細を再申請できる。申請内容はData Protectionで暗号化し、同意した規約版・Discord ID・確認担当・日時を記録する。

`donation_grant`はアカウントUUIDと累計到達額ごとの配布指示。先にManagementDBへ確定し、固定メールIDでゲームDBへ配信してから配信済みにする。通信障害後も同じメールIDで再試行する。ゲームDBのみの初期化後は新規アカウントUUIDへ累計分を配信し、既存UUIDの復元では再配布しない。ManagementDBも初期化した場合は寄付情報が完全リセットとなる。

`donation_notification`はユーザー本人への申請額・否認理由・配信完了通知、`donation_discord_link`は本人IDと公式サーバー参加の検証結果・暗号化OAuthトークンを保存する。詳細は[寄付API](../20_API設計書/feature/37-donations/37_README.md)と[テーブル定義](table-definitions/ManagementDB/dbo.donation.md)。

## 接続と移行

- APIの`ConnectionStrings:Management`を優先。空なら既存`ConnectionStrings:SqlServer`から接続先DB名だけを`ManagementDB`に変更する。旧`ConnectionStrings:WebSite`は参照しない。
- 新規環境では`table-definitions/ManagementDB/init.sql`を適用する。
- 既存環境へネットワーク管理を導入する場合は、API配置前に`table-definitions/ManagementDB/migrations/20260916_managed_network_and_bans.sql`を適用する。既存playerと権限を保持し、専用ロックと台帳で再適用を無処理とする。3DB用db-migrateの対象ではないため、[ネットワーク運用手順](../10_Plugin設計書/feature/33-network/33_5-例外・ログ・運用.md)の明示適用・スキーマ確認を必須とする。
- 既存環境へWeb固定認証を導入する場合は、API配置前に`table-definitions/ManagementDB/migrations/20260917_web_credentials.sql`を適用する。認証情報は`dbo.player`から独立したUUID主キー表であり、ゲーム側テーブルへ外部キーを張らない。
- 既存環境へ信頼済みブラウザを導入する場合は、API配置前に`table-definitions/ManagementDB/migrations/20260920_trusted_admin_browser.sql`を適用する。トークン本体は保存せず、認証情報のセッション世代と最後の管理画面利用日時を保存する。
- 旧WebSiteDBから移行する場合は、APIの書込みを停止し、init適用後に`migrations/20260915-import-website.sql`を適用、件数・UUID・MCID・管理者フラグ・ログイン日時を照合してから新APIへ切り替える。
- 移行はUUIDと管理者フラグを保持する。競合する既存管理レコードは上書きせず停止。適用済み台帳により古いWeb権限を再インポートしない。
- 旧WebSiteDBは移行元として保持し、自動削除しない。切替後の正本はManagementDBだけであり、旧DBを再び書込み先へ戻す際は切替後の差分を別途扱う。
- 既存のゲームDB再構築ツールにManagementDBを追加しない。
