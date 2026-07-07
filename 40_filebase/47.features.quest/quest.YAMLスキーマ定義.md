# quest YAMLスキーマ定義

クエスト単体のマスターデータです。プレイヤーはNPCのクエストボードから受領し、条件達成後に `completion.mode` に応じて自動報酬またはNPC報告で完了します。

## フィールド

| Field | Type | Required | Description |
|:--|:--|:--|:--|
| `schemaVersion` | number | yes | スキーマ版。初期値は `1` |
| `id` | string | yes | クエストID |
| `type` | string | yes | `QUEST` |
| `name` | string | yes | GUI表示名。legacy color code可 |
| `description` | string[] | no | GUI lore 表示用説明 |
| `icon` | string | no | Bukkit `Material`。未指定は `PAPER` |
| `repeat.mode` | string | yes | `ONCE` / `REPEATABLE` / `COOLDOWN` |
| `repeat.cooldownSeconds` | number | no | `COOLDOWN` の再受領待機秒。完了/報酬受取時から開始 |
| `completion.mode` | string | yes | `AUTO` / `NPC` |
| `completion.turnInNpcId` | ref | no | 報告先NPC。未指定時は受領元NPCを運用上の報告先にする |
| `objectives` | object[] | yes | 初期実装は `KILL_MOB` と `GATHERING` |
| `acceptRequirements.items` | object[] | no | 受領に必要なアイテム |
| `rewards.exp` | number | no | AstralRecordアカウント/クラスEXP |
| `rewards.gold` | number | no | 既存Gold通貨 |
| `rewards.items` | object[] | no | 報酬アイテム |

## objectives

| Field | Type | Required | Description |
|:--|:--|:--|:--|
| `id` | string | yes | クエスト内の目標ID |
| `type` | string | yes | `KILL_MOB` / `GATHERING` |
| `targetId` | ref | yes | mob または gathering ID |
| `label` | string | no | GUI表示名 |
| `amount` | number | yes | 必要数 |

## item

| Field | Type | Required | Description |
|:--|:--|:--|:--|
| `itemId` | ref | yes | item ID |
| `category` | string | yes | `material` / `consumable` など |
| `amount` | number | yes | 数量 |
| `consume` | boolean | requirementのみ | 受領時に消費する場合 `true` |
