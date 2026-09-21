# 36-player-activity API 設計

Pluginがゲーム本流と独立したキューから `POST /api/history/activity/batch` へ最大1000件（通常100件程度）のイベントを冪等送信する。`batchId` と各 `eventId` の双方で再送を受理し、別batchに同じeventが含まれても既存行を再作成しない。障害時はPluginが再送し、履歴欠損・API障害によってトレード、ダンジョン、戦闘を失敗させない。

管理者専用の取得APIはすべて `actor_user_uuid` を受け、Web管理権限を確認する。検索期間はUTC半開区間 `[from,to)`、既定30日、最大366日、`pageSize` は1〜100である。応答は `{ page, pageSize, totalCount, items }`。同IP取得APIはIPアドレスを返さず、異なる `userUuid` のペアだけを返す。

| API | 内容 |
|---|---|
| POST `/api/history/activity/batch` | 同IP観測、トレード、ダンジョン、Mob被害・死亡履歴の一括保存 |
| GET `/api/admin/player-activity/same-ip` | 同IP観測した別ユーザーのペア。トレード回数順 |
| GET `/api/admin/player-activity/trades` | 単方向トレードと明細検索 |
| GET `/api/admin/player-activity/dungeons` / `dungeons/players` | 攻略履歴とプレイヤー別踏破集計 |
| GET `/api/admin/player-activity/mobs` / `mobs/{mobId}/players` / `mobs/{mobId}/kills` | Mobランキング、被害者集計、死亡時系列 |

DTO定義は `20_api/AstralRecordApi/AstralRecordApi/Models/PlayerActivityModels.cs` を正本とする。

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

導入時は既存HistoryDBへ[移行SQL](../../../40_Database設計書/table-definitions/HistoryDB/migrations/20260921_player_activity.sql)を適用してから、API、Web、Pluginの順で配置する。導入前に発生したトレード、ダンジョン、Mob行動は復元できない。
