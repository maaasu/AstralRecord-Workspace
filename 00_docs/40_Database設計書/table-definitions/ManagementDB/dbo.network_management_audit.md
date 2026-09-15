# dbo.network_management_audit テーブル定義

設定/BAN更新の実行者・変更前後を保持する監査レコード。運営設定の秘密キーは含めない。

| カラム | 型 | NULL | 内容 |
|---|---|---|---|
| `audit_id` | UNIQUEIDENTIFIER | NO | 主キー |
| `operation` | NVARCHAR(50) | NO | settings.bootstrap / settings.update / ban.update / ban.clear |
| `actor_uuid` | UNIQUEIDENTIFIER | YES | 実行者、bootstrapはNULL |
| `target_uuid` | UNIQUEIDENTIFIER | YES | BAN対象、設定変更はNULL |
| `before_json` | NVARCHAR(MAX) | YES | 変更前、初回作成はNULL |
| `after_json` | NVARCHAR(MAX) | NO | 変更後 |
| `occurred_at_utc` | DATETIME2(3) | NO | 実行UTC、索引あり |

before/afterにISJSON制約を設ける。本体更新が失敗すれば同じトランザクションの監査もロールバックする。管理画面に全削除操作を設けない。
