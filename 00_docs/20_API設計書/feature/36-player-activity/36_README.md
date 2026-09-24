# 36-player-activity API 設計

Pluginがゲーム本流と独立したキューから `POST /api/history/activity/batch` へ最大1000件（通常100件程度）のイベントを冪等送信する。`batchId` と各 `eventId` の双方で再送を受理し、別batchに同じeventが含まれても既存行を再作成しない。障害時はPluginが再送し、履歴欠損・API障害によってトレード、ダンジョン、戦闘を失敗させない。バッチに `bossClears` を追加し、各イベントは `eventId`, `bossId`, `bossName`, `startedAt`, `clearedAt`, `participants` を持つ。各参加者は既存の `player` スナップショット、`damageDealt`（decimal）、`deathCount`（整数）を持つ。攻略時間の逆順、負の数値、空の参加者、同一アカウントの重複は受理しない。

管理者専用の取得APIはすべて `actor_user_uuid` を受け、Web管理権限を確認する。検索期間はUTC半開区間 `[from,to)`、既定30日、最大366日、`pageSize` は1〜100である。応答は `{ page, pageSize, totalCount, items }`。同IP取得APIはIPアドレスを返さず、異なる `userUuid` のペアだけを返す。

| API | 内容 |
|---|---|
| POST `/api/history/activity/batch` | 同IP観測、トレード、ダンジョン、ボス、Mob被害・死亡履歴の一括保存 |
| GET `/api/admin/player-activity/same-ip` | 同IP観測した別ユーザーのペア。トレード回数順 |
| GET `/api/admin/player-activity/trades` | 単方向トレードと明細検索 |
| GET `/api/admin/player-activity/dungeons` / `dungeons/players` | 攻略履歴とプレイヤー別踏破集計 |
| GET `/api/admin/player-activity/bosses` / `bosses/players` | ボス攻略履歴、与ダメージ・死亡回数を含むプレイヤー別集計 |
| GET `/api/admin/player-activity/events` | 既存 `user_history` のログイン・ログアウト・パーティー操作等を検索。`payload_json` は返さない |
| GET `/api/admin/player-activity/mobs` / `mobs/{mobId}/players` / `mobs/{mobId}/kills` | Mobランキング、被害者集計、死亡時系列 |

DTO定義は `20_api/AstralRecordApi/AstralRecordApi/Models/PlayerActivityModels.cs` を正本とする。

ボス参加者の `damageDealt` はPluginの実効ダメージを受け取り、API保存時に小数第3位へ四捨五入（ちょうど中間は絶対値の大きい側）する。小数第4位以下があるだけでは拒否しない。負値、および丸め後に `DECIMAL(18,3)` の範囲を超える値は400とする。

ダンジョンとボスの攻略履歴には、`dungeonId` / `bossId` の完全一致、参加者の `userUuid` / `accountId`、部分一致の `query` を指定できる。`sort=recent`（既定）は攻略日時の降順、`fastest` は攻略時間の昇順、`slowest` は降順。プレイヤー別集計は `sort=count`（既定）が踏破回数の降順、`fastest` が最短攻略時間の昇順、`slowest` が平均攻略時間の降順である。いずれもDBで集計・整列後にページングし、同順位は件数や日時、イベント/アカウントIDで安定化する。異なるダンジョンやボスを混ぜた順位は対象の難易度差を含むため、比較時はID絞り込みを推奨する。

攻略時間は `duration_milliseconds` を正本とし、応答の `durationSeconds` / `bestDurationSeconds` / `averageDurationSeconds` は小数秒の `double` である。ダンジョンのプレイヤー別応答は従来の項目に最短・平均時間を末尾追加する。ボスの参加者には `player`, `damageDealt`, `deathCount`、プレイヤー別応答には `player`, `clearCount`, `firstClearedAt`, `lastClearedAt`, `bestDurationSeconds`, `averageDurationSeconds`, `totalDamageDealt`, `totalDeathCount` を返す。

`events` は `eventType` の完全一致、`userUuid`、`query`（message/type/source の部分一致）で絞る。応答項目は `historyId`, `userUuid`, `eventTime`, `eventType`, `source`, `message`, `player`。`player` は対象ユーザーについて最後に保存された接続観測の `userUuid`, `accountId`, `mcid`, `accountName` であり、観測がなければ null。イベント当時の使用アカウントを確定する値ではない。`payload_json` は返さない。`message` は既存のログイン・パーティー記録ではMCID、パーティーID等を含むため管理者だけに公開する。

```json
{
  "batchId": "f1b1e4b4-3e6e-45cb-8b37-8c6a9ad64cc0",
  "trades": [{
    "eventId": "3a095fa7-2a5e-4d38-a7d6-f08a7f67c2aa",
    "completedAt": "2026-09-21T12:00:00Z",
    "source": { "userUuid": "11111111-1111-1111-1111-111111111111", "accountId": "22222222-2222-2222-2222-222222222222", "mcid": "Alice", "accountName": "AliceMain" },
    "destination": { "userUuid": "33333333-3333-3333-3333-333333333333", "accountId": "44444444-4444-4444-4444-444444444444", "mcid": "Bob", "accountName": "BobMain" },
    "items": [{ "itemId": "iron_ingot", "itemName": "鉄インゴット", "quantity": 3 }],
    "gold": 100
  }]
}
```

Mobランキングとプレイヤー別被害集計は `windowEndedAt`、死亡履歴は `occurredAt` を期間判定に用いる。Pluginは管理対象Mobだけを送信し、通常のダメージは Mob・被害プレイヤー・1分程度の窓で集約する。

入力検証の `400`（将来のサイズ制限時は `413`、意味検証追加時は `422`）はPluginが当該バッチを破棄してよい恒久エラーとする。`429`、`5xx`、ネットワーク例外は一時エラーとして同じ `batchId` と `eventId` を再送する。Pluginの送信予約・実行中taskは常に1つだけとし、一時失敗後は少なくとも60秒待ってから再送する。

導入時は既存HistoryDBへ[基礎移行SQL](../../../40_Database設計書/table-definitions/HistoryDB/migrations/20260921_player_activity.sql)と[攻略時間・ボス移行SQL](../../../40_Database設計書/table-definitions/HistoryDB/migrations/20260924_boss_activity_duration.sql)を順に適用してから、API、Web、Pluginの順で配置する。後者はダンジョンの既存行からミリ秒所要時間を計算し、旧APIの書き込み中も計算列で値を維持する。導入前に発生したトレード、ダンジョン、ボス、Mob行動は復元できない。
