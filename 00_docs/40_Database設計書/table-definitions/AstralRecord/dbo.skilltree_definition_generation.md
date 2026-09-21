# dbo.skilltree_definition_generation

Plugin が正規化したスキルツリー定義スナップショットの世代台帳です。

| カラム | 型 | 説明 |
|:--|:--|:--|
| `definition_generation_id` | NVARCHAR(64) | canonical snapshot UTF-8 の小文字 SHA-256 |
| `canonical_snapshot_json` | NVARCHAR(MAX) | 構造・ノード・条件・ポイント・効果・判定関連定義を含む正規化済みJSON |
| `created_at_utc` | DATETIME2(3) | API が初めて登録した時刻 |

同じIDで異なるJSONは拒否する。Seeder時刻やファイル配置時刻を世代IDの代用にしない。
