# Player Activity History tables

管理画面向けのプレイヤー行動履歴はすべて `HistoryDB` に保存する。ゲームDBのアカウント、インベントリ、冒険記録には外部キーを張らないため、ゲームDBのリセットまたはアカウント削除後も記録時点の UUID、MCID、アカウント名を表示できる。

| テーブル | 用途 | 主キー |
|---|---|---|
| `player_activity_batch` | Plugin送信バッチの冪等受理台帳 | `batch_id` |
| `player_ip_observation` | ログイン時の同IP照合用観測。IPは管理APIで返さない | `event_id` |
| `player_trade_activity` / `_item` | 単方向のGold・アイテム移転と明細 | `event_id` / `event_id,line_number` |
| `dungeon_clear_activity` / `_participant` | 攻略時刻、参加者、移動距離と観測数 | `event_id` / `event_id,account_id` |
| `boss_clear_activity` / `_participant` | ボス攻略時刻、参加者、与ダメージと死亡回数 | `event_id` / `event_id,account_id` |
| `mob_damage_summary` | Mobからプレイヤーへの実HP被害を1分程度で集計した窓 | `event_id` |
| `mob_player_death` | Mobによるプレイヤー死亡イベント | `event_id` |

`distance_meters = NULL` は観測不足であり、`0` は移動なしを表す。`mob_player_death` にダメージ量を複製せず、ダメージ集計は `mob_damage_summary` を正本とする。

ダンジョンとボスの `duration_milliseconds` は `DATEDIFF_BIG(MILLISECOND, started_at, cleared_at)` による永続計算列とする。既存ダンジョン行の時間も移行時に自動算出され、旧APIからの追加行にも値が入る。管理APIはこの値をSQL Serverで並び替え・集計・ページングし、秒表示へ変換する。ボス参加者の `damage_dealt` は `DECIMAL(18,3)`、`death_count` は非負整数。両テーブルはイベント主キーと参加者の `(event_id, account_id)` 主キーを持つ。

新規DBは `init.sql` で作成する。既存HistoryDBには API 配置前に `60_tool/15-history-db-migrate.bat` を実行する。同runnerは `migrations/20260921_player_activity.sql` と `migrations/20260924_boss_activity_duration.sql` を順に `ConnectionStrings:History` の `HistoryDB` へ適用し、`dbo.schema_migration` にSQL hash付きで記録する。この履歴はベストエフォートであり、書込み失敗をゲーム進行の失敗として扱わない。
