# SkillTree YAML スキーマ定義

`40_filebase/35.features.skilltree/*.yml` に配置するスキルツリーノード定義の schema です。
ノードごとに次の 2 系統の効果を持てます。

- `skillIds`: ノード解放時にプレイヤーが恒常的に得るスキル
- `statuses`: ノード解放時に永続プレイヤーへ付与されるステータス

進行データの責務:

- プレイヤー単位の動的進行状態（`skillPoints`、解放済み `nodeId` 一覧）は plugin ローカル YAML には保持せず、`account-skilltree` API と AstralRecord DB を正本とする
- `positionId` の参照先である `skilltree_structure.yml` は `skill_tree` ワールド上の座標配置を表す plugin データファイルであり、サーバローカルで管理する

`skilltree_structure.yml` の契約:

- 配置場所: plugin data folder 直下の `skilltree_structure.yml`
- 管理対象: `positions[]` と `edges[]`
- `positions[]`: `{ id, world, x, y, z }`
- `edges[]`: `{ left, right }`
- 用途: filebase ノード定義の `positionId` と、Purpur サーバ上の `skill_tree` ワールド座標を結び付ける
- 非対象: プレイヤーごとの解放状態、スキルポイント、監査履歴

仕様を簡潔に先に整理すると次のようになります。

## 概要

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `schemaVersion` | Integer | 必須 | - | 現在は `1` |
| `id` | String | 必須 | - | ノードID |
| `positionId` | String | 必須 | - | `skilltree_structure.yml` の position ID |
| `name` | String | 必須 | - | 表示名 |
| `icon` | String | 任意 | `NETHER_STAR` | Material 名 |
| `lore` | List<String> | 任意 | `[]` | ノード説明 |
| `tags` | List<String> | 任意 | `[]` | 任意タグ |
| `skillIds` | List<String> | 任意 | `[]` | 解放時に得るスキル ID 一覧 |
| `statuses` | List<Map> | 任意 | `[]` | 解放時に付与するステータス一覧 |
| `statuses[].status` | String | 必須 | - | `StatusType` 名 |
| `statuses[].type` | String | 任意 | `FLAT` | `FLAT` または `SCALAR` |
| `statuses[].value` | Number | 必須 | - | 付与値。`SCALAR` の場合は `0.10 = 10%` |

## 補足

- `positionId` は filebase node 定義と、サーバローカル `skilltree_structure.yml` の位置定義をつなぐキーである
- `skillIds` に含まれるスキルがパッシブで `passive.bindRequired: false` の場合、ノード解放だけで恒常効果になる
- `skillIds` に含まれるスキルがパッシブで `passive.bindRequired: true` の場合、ノード解放後にパッシブスロットへ設定された時だけ効果になる
- `statuses` はパッシブスキルを実装せず、ノード解放状態から直接プレイヤーステータスへ反映される
- `statuses.type` は schema 上の受理値で、実装時は `FLAT` として扱います

## 例

### スキル付与ノード

```yaml
schemaVersion: 1
id: starter_power
positionId: root_001
name: "&f始まりの力"
icon: IRON_SWORD
lore:
  - "&7スキルツリーの最初のノード。"
skillIds:
  - iron_will
tags:
  - starter
```

### ステータス付与ノード

```yaml
schemaVersion: 1
id: starter_vital
positionId: root_002
name: "&a生命強化"
icon: APPLE
statuses:
  - status: MAX_HEALTH
    type: FLAT
    value: 10
  - status: DEFENSE
    type: SCALAR
    value: 0.05
tags:
  - starter
  - status
```

### 混合ノード

```yaml
schemaVersion: 1
id: hybrid_guard
positionId: mid_010
name: "&b守りの構え"
icon: SHIELD
skillIds:
  - iron_will
statuses:
  - status: MAX_HEALTH
    type: FLAT
    value: 15
tags:
  - hybrid
```
