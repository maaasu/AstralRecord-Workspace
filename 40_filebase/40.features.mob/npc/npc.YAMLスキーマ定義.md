# NPC YAML スキーマ定義

NPC 用の filebase YAML 定義です。

共通キーの `schemaVersion`, `id`, `type`, `category`, `name`, `entityType`, `baseStats`, `equipment`, `ai.idle` は
[../mob.YAMLスキーマ定義.md](../mob.YAMLスキーマ定義.md) を参照してください。

NPC は戦闘を行わないため、`ai.targeting`, `ai.combat`, `drops` は定義しません。

`entityType` には Bukkit EntityType に加えて Bukkit block Material（例: `BARREL`, `ANVIL`）を指定できます。
block Material の場合は `BlockDisplay` による fakeblock として表示し、通常 NPC と同じ display text、ambient particle、左クリック/右クリック interaction を扱います。
配置座標は fakeblock の中心として扱います。`CHEST` / `TRAPPED_CHEST` / `ENDER_CHEST` は BlockDisplay で描画されないため、表示用 block Material は `BARREL` に正規化されます。

---

## 追加キー

| キー | 型 | 必須 | 説明 |
|:--|:--|:--:|:--|
| `damageImmune` | Boolean | 推奨 | `true` を推奨。プレイヤーや環境ダメージを受けない NPC として扱う |
| `interactions.leftClick` | Action[] | - | 左クリック時に順番に実行するアクション |
| `interactions.rightClick` | Action[] | - | 右クリック時に順番に実行するアクション |

### Action

| キー | 型 | 必須 | 説明 |
|:--|:--|:--:|:--|
| `id` | String | yes | アクション ID。現在は `message` / `gui` をサポート |
| `params` | Map<String, String> | - | アクションごとの追加パラメータ |

### サポートされるアクション

| `id` | `params` | 説明 |
|:--|:--|:--|
| `message` | `message` | チャットメッセージを送信する |
| `gui` | `type`, `shopId` | GUI を開く。`SHOP` は `shopId` 必須、`SELL` は売却 GUI、`CLASS` は職業選択 GUI、`STORAGE` はストレージ GUI、`EQUIPMENT_ENHANCE` は装備強化 GUI |

---

## 配置運用

NPC YAML はテンプレート定義のみを管理します。実際の配置座標は plugin データフォルダの `npc_locations.yml` に保存されます。

- 配置コマンド: `/mob npc place <npcId> [x y z]`
- 座標変更: 既存配置を削除して再配置
- 一括読込: `npc_locations.yml` の内容をワールド読込後に復元

---

## 例

### 職業案内 NPC

```yaml
schemaVersion: 1
id: class_guide
type: MOB
category: NPC
name: "&b職業案内人ミラ"
title: "&7Class Guide"
level: 1
entityType: PLAYER
nameVisible: true
damageImmune: true
icon: BOOK
lore:
  - "&7職業の特徴と転向条件を案内する。"
  - "&7右クリックで職業選択 GUI を開ける。"
tags:
  - npc
  - class_guide

baseStats:
  - status: MAX_HEALTH
    value: 100
  - status: MOVEMENT_SPEED
    value: 0

ai:
  idle:
    behavior: STATIONARY

interactions:
  leftClick:
    - id: message
      params:
        message: "&b職業案内人ミラ&7: 進みたい道を選んでみて。"
  rightClick:
    - id: gui
      params:
        type: CLASS
```

### 装備商人 NPC

```yaml
schemaVersion: 1
id: equipment_merchant
type: MOB
category: NPC
name: "&6装備商人ダレン"
title: "&7Starter Equipment"
level: 1
entityType: PLAYER
nameVisible: true
damageImmune: true
icon: EMERALD
lore:
  - "&7旅立ちに必要な初期装備を扱う商人。"
tags:
  - npc
  - shop

baseStats:
  - status: MAX_HEALTH
    value: 100
  - status: MOVEMENT_SPEED
    value: 0

ai:
  idle:
    behavior: STATIONARY

interactions:
  rightClick:
    - id: gui
      params:
        type: SHOP
        shopId: starter_equipment_shop
```
