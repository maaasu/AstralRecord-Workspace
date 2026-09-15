# dbo.network_ban テーブル定義

ユーザーUUID単位のBAN正本。全アカウントに適用し、ゲームDBリセットで削除しない。

| カラム | 型 | NULL | 内容 |
|---|---|---|---|
| `user_uuid` | UNIQUEIDENTIFIER | NO | 主キー |
| `revision` | INT | NO | 1以上、更新の期待版照合 |
| `is_banned` | BIT | NO | BAN指定状態。falseは明示解除 |
| `expires_at_utc` | DATETIME2(3) | YES | NULLは無期限、期限≦現在UTCで失効 |
| `reason` | NVARCHAR(500) | YES | 理由 |
| `updated_at_utc` | DATETIME2(3) | NO | 更新UTC |
| `updated_by` | UNIQUEIDENTIFIER | NO | 実行者UUID。認証済みRPG Consoleはnil |

`is_banned=false`では期限NULLをCHECKで要求する。有効一覧は`(is_banned, expires_at_utc)`索引を使用する。管理行があれば解除済み・期限切れでも旧ゲームDBのBANへ戻らない。行がない場合だけ旧BANを参照する。更新と監査は同一トランザクション。未Webログインの対象は`dbo.player`へ既定の非公開・非WebAdminで識別情報を保持する。
