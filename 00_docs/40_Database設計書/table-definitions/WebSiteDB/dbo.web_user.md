# dbo.web_user テーブル定義

Webサイトへログインに成功したプレイヤーを、MinecraftのプレイヤーUUID単位で管理するテーブルです。ゲームDBの `dbo.user` を複製せず、Web管理権限の正本もこのテーブルに限定します。

## カラム

| カラム | 型 | NULL | 既定値 | 内容 |
|---|---|---|---|---|
| `user_uuid` | `UNIQUEIDENTIFIER` | NO |  | MinecraftプレイヤーUUID。主キー |
| `mcid` | `NVARCHAR(100)` | NO |  | 成功時点のMCID |
| `web_admin` | `BIT` | NO | `0` | Web管理機能の許可フラグ |
| `first_login_at` | `DATETIME2(3)` | NO |  | 初回Webログイン成功日時（UTC） |
| `last_login_at` | `DATETIME2(3)` | NO |  | 最終Webログイン成功日時（UTC） |

## 更新規則

- APIがログインコードを正常に消費した後だけupsertする。
- 初回は `web_admin = 0` で作成する。ゲーム側の `permission` は参照せず、初期管理者も自動作成しない。
- 再ログイン時は `mcid` と `last_login_at` だけを更新し、既存 `web_admin` と `first_login_at` は保持する。
- 管理者指定は運用担当者が明示的にSQLで行う。例: `UPDATE [WebSiteDB].[dbo].[web_user] SET [web_admin] = 1 WHERE [user_uuid] = 'プレイヤーUUID';`
- Web画面は要求ごとにAPI経由で `web_admin` を照会する。フラグを `0` に戻した次の要求から管理機能を拒否し、API照会障害時も拒否する。

## 制約・インデックス

- `PK_web_user`: `user_uuid`
- `DF_web_user_web_admin`: `web_admin = 0`
- `CK_web_user_mcid_not_blank`: 空白だけのMCIDを禁止
- `CK_web_user_login_order`: 最終ログイン日時が初回ログイン日時より前になることを禁止
- `IX_web_user_web_admin`: Web管理者の照会補助

## 配置

- API設定キーは `ConnectionStrings:WebSite`。未設定の場合、APIは `ConnectionStrings:SqlServer` のSQL認証情報を維持したまま接続先DB名だけを `WebSiteDB` に置換する。
- 本番初回配置時は、このディレクトリの `init.sql` をSQL Serverへ適用する。既存テーブルを破壊しない再実行安全なSQLである。
- `60_tool/db-migrate` と `db-reset-except-release-notes` は既存DB専用であり、`WebSiteDB` を作成・再作成しない。Web利用者情報とWeb管理権限を保持するため、これらのツールで代替しない。
