# ManagementDB設計

## 目的と境界

`ManagementDB` はプレイヤー識別・運営上の権限などを半永久的に保持する管理DB。Webサイトやゲーム内キャラクターの寿命から独立し、通常運用でDBリセットを行わない。

- 現在の実装: `dbo.player`（UUID単位のプレイヤー情報とWeb管理フラグ）、`dbo.schema_migration`（適用済み移行台帳）、`dbo.network_settings`（ネットワーク設定）、`dbo.network_ban`（ユーザーBAN）、`dbo.network_management_audit`（設定・BAN更新監査）。
- ゲームデータの正本は引き続き`AstralRecord`。レベル・所持品・キャラクターIDはManagementDBへ複製しない。
- ゲームDB・MasterDataDB・HistoryDBのリセット、再構築、アカウント削除からManagementDBを除外する。全DB列挙や名前prefixによる一括削除へ含めない。
- 更新はデータを保持する個別migrationで行う。DBごとのバックアップと復元手順を管理し、ゲームDB復元に連動して古いManagementDBへ戻さない。
- 特別なデータ訂正・削除が必要な場合は個別に対象と理由を確認する。通常の運営操作に全消去を設けない。

## 将来の寄付履歴（未実装の設計方針）

寄付受付を追加する際は、合計金額をプレイヤー行に直接上書きする方式ではなく、独立した寄付履歴テーブルをManagementDB内に設ける。

- 各寄付を固有ID、プレイヤーUUID、金額、通貨、受付・確定日時、外部決済識別子等で記録する。実装時に決済方式に合わせて契約を確定する。
- 関連付けは`ManagementDB.dbo.player.player_uuid`とする。キャラクターID、ゲームDBのuser行、MCIDには依存させない。
- 同じ決済通知の再送で二重計上しない一意性を設ける。返金・取消は元履歴を消す代わりに関連記録として追跡可能にする。
- 合計は確定履歴と返金等から通貨別に集計する。異なる通貨を無条件に加算しない。
- ゲームデータが削除・リセットされた状態でも、UUIDで過去の寄付履歴と累計を参照できるようにする。

寄付テーブル、決済連携、集計API、画面は今回の実装対象外。

## 接続と移行

- APIの`ConnectionStrings:Management`を優先。空なら既存`ConnectionStrings:SqlServer`から接続先DB名だけを`ManagementDB`に変更する。旧`ConnectionStrings:WebSite`は参照しない。
- 新規環境では`table-definitions/ManagementDB/init.sql`を適用する。
- 既存環境へネットワーク管理を導入する場合は、API配置前に`table-definitions/ManagementDB/migrations/20260916_managed_network_and_bans.sql`を適用する。既存playerと権限を保持し、専用ロックと台帳で再適用を無処理とする。3DB用db-migrateの対象ではないため、[ネットワーク運用手順](../10_Plugin設計書/feature/33-network/33_5-例外・ログ・運用.md)の明示適用・スキーマ確認を必須とする。
- 旧WebSiteDBから移行する場合は、APIの書込みを停止し、init適用後に`migrations/20260915-import-website.sql`を適用、件数・UUID・MCID・管理者フラグ・ログイン日時を照合してから新APIへ切り替える。
- 移行はUUIDと管理者フラグを保持する。競合する既存管理レコードは上書きせず停止。適用済み台帳により古いWeb権限を再インポートしない。
- 旧WebSiteDBは移行元として保持し、自動削除しない。切替後の正本はManagementDBだけであり、旧DBを再び書込み先へ戻す際は切替後の差分を別途扱う。
- 既存のゲームDB再構築ツールにManagementDBを追加しない。
