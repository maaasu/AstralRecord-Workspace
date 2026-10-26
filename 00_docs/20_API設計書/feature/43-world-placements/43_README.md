# ワールド配置 API

スポナー、NPC、ウェイストーンの**配置**をマスター定義と分離し、専用 `WorldPlacementDevDB`（編集）と `WorldPlacementDB`（チャンネル配信）で管理する。プレイヤーごとのウェイストーン開放状態は従来のプレイヤー DB に残る。

## 契約

すべての操作で共通 `X-Api-Key` が必要。Dev 編集は追加で `X-World-Placement-Key: <WorldPlacements:WriteKey>`、同期は別の `SyncKey` を要求する。未設定のキーや接続先は無効扱い。`channels` への単件・一括編集は提供しない。

| 操作 | 内容 |
|:--|:--|
| `GET /api/world-placements/{dev\|channels}` | 現行の一括スナップショット。`ETag` は revision。`If-None-Match` 一致時 304。 |
| `PUT /api/world-placements/dev` | `{expectedRevision,placements}` の全件原子的置換。 |
| `PUT /api/world-placements/dev/{placementId}` | `{expectedRevision,placement}` の単件 upsert。URL と placement ID は一致必須。 |
| `DELETE /api/world-placements/dev/{placementId}?expectedRevision=...` | 単件削除。 |
| `POST /api/world-placements/sync` | `{expectedDevRevision,expectedChannelRevision}` を検証し、Dev 現行スナップショットを Channels に複写。 |

GET 応答例:

```json
{"schemaVersion":1,"revision":"0","placements":[]}
```

Dev 一括置換リクエスト例（初期スナップショットへの登録）:

```json
{"expectedRevision":"0","placements":[{"placementId":"spawner:1","kind":"spawner","worldId":"world:dev","worldName":"world_dev","templateId":"mob:1","x":10,"y":64,"z":20,"yaw":0,"pitch":0,"enabled":true,"waystone":null}]}
```

成功時は更新後の全件スナップショット（この例では `revision:"1"`）を返す。単件 `PUT` は `{"expectedRevision":"1","placement":{...}}`、同期は `{"expectedDevRevision":"1","expectedChannelRevision":"0"}` を送る。

`placement` は `placementId`, `kind` (`spawner` / `npc` / `waystone`), `worldId`, `worldName`, `templateId`, `x`, `y`, `z`, `yaw`, `pitch`, `enabled`, `waystone` を持つ。`worldId` は論理 ID、`worldName` はランタイムのワールド名。未解決の旧ワールドも値を残す。`spawner` / `npc` には `templateId` が必須。`waystone` は `templateId:null` と `waystone:{id,name,lockEnabled,unlockGold,icon,createdAt,createdBy}` が必須。waystone ID は既存の開放状態と一致する安定 ID を保持し、同じ placementId の更新では変更できない。

配置 ID と waystone ID の重複、空の world ID/name、非有限または範囲外の座標、種類に合わない情報は 400。配置・マスター・waystone ID は ASCII 英数字と `_`、`-`、`.`、`:` のみ。スポナーの X/Y/Z はブロック整数とし、有効なスポナー同士・NPC同士の同ワールド同座標重複を拒否する。revision 不一致は 409。編集と同期は各 DB 行をロックした SQL transaction で確定し、失敗時に部分更新しない。版番号は現行状態の競合検出にだけ使用し、過去版を保持しない。

## 配置 DB 適用

DB 管理者が `WorldPlacementDevDB` と `WorldPlacementDB` を明示作成し、それぞれを選択して `00_docs/40_Database設計書/table-definitions/WorldPlacementDB/init.sql` を適用する。API の起動時に DB 作成や投入は行わない。同期は両 DB が同一 SQL Server 上で同じログインを使う設定だけで動作する。Dev 単体ホストでは Channels 接続を空のままにできる。
