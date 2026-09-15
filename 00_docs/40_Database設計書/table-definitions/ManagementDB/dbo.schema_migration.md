# dbo.schema_migration テーブル定義

長期保持DBに適用済みのmigrationを記録する。通常のゲームリセットとは無関係に保持する。

| カラム | 型 | NULL | 既定値 | 内容 |
|---|---|---|---|---|
| `migration_id` | `NVARCHAR(150)` | NO | なし | 移行の識別子。主キー |
| `applied_at` | `DATETIME2(3)` | NO | UTC現在日時 | 適用完了日時 |

移行処理と同じtransactionで完了を記録する。アプリケーションの通常処理から削除・更新しない。
