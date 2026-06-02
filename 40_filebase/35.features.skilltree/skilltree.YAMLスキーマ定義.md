# SkillTree YAML スキーマ定義

プレイヤー単位の巨大スキルツリーに配置するノードマスタです。

## 配置場所

`40_filebase/35.features.skilltree/*.yml`

## スキーマ

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `schemaVersion` | Integer | ○ | - | 現在は `1` |
| `id` | String | ○ | - | スキルノードID |
| `positionId` | String | ○ | - | plugin data の `skilltree_structure.yml` に保存されるポジションID |
| `name` | String | ○ | - | 表示名。`&` カラーコード可 |
| `icon` | String | - | `NETHER_STAR` | PLAYER mode で表示するドロップアイテム Material |
| `lore` | List<String> | - | empty | ホットバー詳細表示用の説明 |
| `tags` | List<String> | - | empty | 将来の検索・分類用タグ |

## YAML 例

```yaml
schemaVersion: 1
id: starter_power
positionId: root_001
name: "&f基礎鍛錬"
icon: IRON_SWORD
lore:
  - "&7スキルツリーの開始ノード。"
  - "&8現時点では解放状態のみを保持する。"
tags:
  - starter
```
