# NPC YAML スキーマ定義

NPC（非戦闘Mob）の固有フィールド定義。

共通フィールド（`schemaVersion`, `id`, `type`, `category`, `name`, `entityType`, `baseStats`, `equipment`, `ai.idle` 等）は
[mob.YAMLスキーマ定義.md](../mob.YAMLスキーマ定義.md) を参照してください。

NPC は戦闘を行わないため、**ターゲット選択（`ai.targeting`）・戦闘AI（`ai.combat`）・ドロップ（`drops`）は不要**です。
行動AIは共通スキーマの `ai.idle` のみで制御します。

---

## カテゴリ固有の制約

| 項目             | 状態 | 説明                      |
|:---------------|:---|:------------------------|
| `ai.targeting` | 不要 | NPCは敵対しない               |
| `ai.combat`    | 不要 | NPCは戦闘しない               |
| `drops`        | 不要 | NPCはドロップを持たない           |
| `ai.idle`      | 必須 | 共通スキーマで定義済み。NPCの行動はこれのみ |
| `damageImmune` | 任意 | `true` の場合、プラグイン側のダメージ処理を無効化する。NPC は `true` 推奨 |
| `interactions` | 任意 | 左クリック・右クリック時のアクション定義 |

---

## NPC インタラクション定義

`interactions` は NPC に対する左クリック・右クリック時の動作を宣言する任意フィールドです。
クリックごとにアクション配列を指定し、プラグイン側の実行系は `id` と `params` を見て処理を選択します。
Plugin は一定距離内にある視線先 NPC に対して、実体を直接クリックできない場合でも `leftClick` / `rightClick` インタラクトを発生させます。

| フィールド | 型 | 必須 | 説明 |
|:--|:--|:--|:--|
| `interactions.leftClick` | Action[] | - | 左クリック時に実行するアクション。未指定または空配列なら何もしない |
| `interactions.rightClick` | Action[] | - | 右クリック時に実行するアクション。未指定または空配列なら何もしない |

### Action

| フィールド | 型 | 必須 | 説明 |
|:--|:--|:--|:--|
| `id` | String | ○ | アクション ID。初期定義は `message` / `gui` |
| `params` | Map<String, String> | - | アクションごとのパラメータ。未指定時は空オブジェクト扱い |

### アクション ID

| `id` | `params` | 説明 |
|:--|:--|:--|
| `message` | `message` | プレイヤーに送るメッセージ本文。Minecraft カラーコードを使用可能 |
| `gui` | `type`, `shopId` | 開く GUI の種類。`SHOP` は `shopId` で対象ショップ ID を指定する。`SELL` は売却 GUI を開く |

`params` のキーは文字列で定義します。数値や真偽値が必要な場合も、まずは文字列として記述してください。

---

## NPC 配置管理

NPC マスタ YAML は NPC の定義のみを管理します。実際の配置座標は Plugin のデータフォルダ配下 `npc_locations.yml` に保存されます。

- 配置コマンド: `/mob npc place <npcId> [x y z]`
- 座標省略時: コマンド実行プレイヤーの現在位置
- 起動時: `npc_locations.yml` の全配置を読み込み、ロード済みワールドにスポーン
- ワールドロード時: 当該ワールドの未スポーン NPC をスポーン
- 配置アイテム: 不要

---

## YAML 例

### 例1: 固定NPC（プレイヤースキン型）

```yaml
schemaVersion: 1
id: village_elder
type: MOB
category: NPC
name: "&e村長マルクス"
title: "&7始まりの村 村長"
level: 1
entityType: PLAYER
skin:
  texture: "ewogICJ0aW1lc3RhbXAi..."
  signature: "dGVzdFNpZ20hdHVyZQ..."
nameVisible: true
damageImmune: true
icon: VILLAGER_SPAWN_EGG
lore:
  - "&7この村を長年治めてきた長老。"
  - "&7冒険者に助言を与えてくれる。"
tags:
  - npc
  - quest_giver

equipment:
  mainHand:
    ref: item:wooden_staff

baseStats:
  - status: MAX_HEALTH
    value: 100

ai:
  idle:
    behavior: STATIONARY

interactions:
  leftClick:
    - id: message
      params:
        message: "&e村長マルクス&7: よく来たな、冒険者よ。"
  rightClick:
    - id: gui
      params:
        type: QUEST
```

### 例2: 巡回NPC（バニラエンティティ型）

```yaml
schemaVersion: 1
id: traveling_merchant
type: MOB
category: NPC
name: "&6旅の商人"
title: "&7各地を巡る行商人"
level: 1
entityType: WANDERING_TRADER
nameVisible: true
damageImmune: true
icon: EMERALD
lore:
  - "&7世界中を旅しながら商いをしている。"
  - "&7珍しいアイテムを取り扱っている。"
tags:
  - npc
  - merchant

baseStats:
  - status: MAX_HEALTH
    value: 80

ai:
  idle:
    behavior: WANDER
    wanderRadius: 10
    speed: 0.6

interactions:
  rightClick:
    - id: gui
      params:
        type: SHOP
        shopId: starter_shop
```
