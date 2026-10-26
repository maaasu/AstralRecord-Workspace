# dbo.world_placement_snapshot

`WorldPlacementDevDB` と `WorldPlacementDB` にそれぞれ一行だけ保持する。API 起動では作成せず、対象 DB を指定して `init.sql` を明示実行する。

| 列 | 型 | 内容 |
|:--|:--|:--|
| `id` | int PK | 常に `1` |
| `revision` | bigint | CAS 用の単調増加版番号。初期値 `0` |
| `placements_json` | nvarchar(max) | schemaVersion 1 の配置配列。スポナー、NPC、ウェイストーンの配置だけを保持 |
| `updated_at_utc` | datetime2(7) | 最終更新 UTC |

プレイヤーのウェイストーン開放状態やマスター定義は本 DB に保存しない。Dev から Channels への公開は双方の revision を検証した単一 SQL Server トランザクションで現行スナップショットのみ複写する。履歴は保持しない。
