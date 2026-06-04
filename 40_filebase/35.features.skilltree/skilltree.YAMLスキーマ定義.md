# SkillTree YAML スキーマ定義

`40_filebase/35.features.skilltree/*.yml` に配置するスキルツリーノード定義の schema です。

ノードは次の 2 種類の効果を持てます。

- `skillIds`: ノード解放時にプレイヤーが所持扱いになるスキル
- `statuses`: ノード解放時に直接プレイヤーへ加算されるステータス

両方を同時に定義することも可能です。

## 項目

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `schemaVersion` | Integer | 必須 | - | 現在は `1` |
| `id` | String | 必須 | - | ノード ID |
| `positionId` | String | 必須 | - | `skilltree_structure.yml` の position ID |
| `name` | String | 必須 | - | 表示名 |
| `icon` | String | 任意 | `NETHER_STAR` | Material 名 |
| `lore` | List<String> | 任意 | `[]` | ノード説明 |
| `tags` | List<String> | 任意 | `[]` | 任意タグ |
| `skillIds` | List<String> | 任意 | `[]` | 解放時に所持扱いになるスキル ID 一覧 |
| `statuses` | List<Map> | 任意 | `[]` | 解放時に直接加算するステータス一覧 |
| `statuses[].status` | String | 必須 | - | `StatusType` 名 |
| `statuses[].type` | String | 任意 | `FLAT` | `FLAT` または `SCALAR` |
| `statuses[].value` | Number | 必須 | - | 加算値。`SCALAR` の場合は `0.10 = 10%` |

## 補足

- `skillIds` に含まれるスキルがパッシブで `passive.bindRequired: false` の場合、ノード解放だけで常時有効になります。
- `skillIds` に含まれるスキルがパッシブで `passive.bindRequired: true` の場合、ノード解放後にパッシブスロットへ設定した時だけ有効になります。
- `statuses` はパッシブスキルを経由せず、ノード解放状態から直接プレイヤーステータスへ反映されます。
- `statuses.type` は schema 上は省略可能で、未指定時は `FLAT` として扱います。

## 例

### スキル付与ノード

```yaml
schemaVersion: 1
id: starter_power
positionId: root_001
name: "&f初歩の覚醒"
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

### 複合ノード

```yaml
schemaVersion: 1
id: hybrid_guard
positionId: mid_010
name: "&b護りの心得"
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