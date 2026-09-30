# dbo.skilltree_definition_generation

Plugin が正規化したスキルツリー定義スナップショットの世代台帳です。

| カラム | 型 | 説明 |
|:--|:--|:--|
| `definition_generation_id` | NVARCHAR(64) | canonical snapshot UTF-8 の小文字 SHA-256 |
| `canonical_snapshot_json` | NVARCHAR(MAX) | 構造・ノード・条件・ポイント・効果・判定関連定義を含む正規化済みJSON |
| `created_at_utc` | DATETIME2(3) | API が初めて登録した時刻 |
| `patch_version` | BIGINT NULL | 管理APIによる明示公開順。正数は公開済み、0は機能導入前の既知定義、NULLは新規未公開 |

同じIDで異なるJSONは拒否する。Seeder時刻やファイル配置時刻を世代IDの代用にしない。

公開番号は再起動・同一定義の再配布で増やさない。正数だけを対象にした一意index `UX_skilltree_definition_generation_patch` と非負check制約を持つ。ログイン時は公開済みの移行先が保存世代より新しい場合だけ保持移行できる。既存定義への0設定は `20260930_skilltree_login_patch.sql` の列追加時に一度だけ行い、再実行で未公開定義を基準扱いにしない。プレイヤーの世代や進行状態をこのDDLで更新しない。

`introduced_after_patch_version` (BIGINT NOT NULL、既定0) は初回登録時点の最大公開番号を記録する。定義登録と公開は同じ台帳ロックで直列化する。未公開候補はこの番号が現時点の最大公開番号と一致する場合だけ公開でき、別定義の公開をまたいだ古い候補を時刻比較や再起動で昇格させない。
