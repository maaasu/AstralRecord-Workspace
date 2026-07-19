# quest_board YAMLスキーマ定義

NPC右クリック時に開くクエスト受領GUIのマスターデータです。NPC側の interaction では `type: QUEST` と `boardId` を指定します。

## スキーマ定義

| Field | Type | Required | Description |
|:--|:--|:--|:--|
| `schemaVersion` | number | yes | スキーマ版。初期値は `1` |
| `id` | string | yes | クエストボードID |
| `type` | string | yes | `QUEST_BOARD` |
| `name` | string | yes | GUIタイトル |
| `quests` | object[] | yes | 表示するクエスト |

### quests

| Field | Type | Required | Description |
|:--|:--|:--|:--|
| `questId` | ref | yes | 表示する quest ID |
| `page` | number | no | 1始まりのページ番号 |
| `slot` | number | no | 0-27 の論理スロット。`row`/`column` より優先 |
| `row` | number | no | 1-4 |
| `column` | number | no | 1-7 |
