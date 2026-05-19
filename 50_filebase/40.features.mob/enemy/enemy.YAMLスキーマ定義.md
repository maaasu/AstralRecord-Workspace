# Enemy YAML スキーマ定義

Enemy（通常エネミー）の固有フィールド定義。

共通フィールド（`schemaVersion`, `id`, `type`, `category`, `name`, `entityType`, `baseStats`, `equipment`, `ai.idle` 等）は
[mob.YAMLスキーマ定義.md](../mob.YAMLスキーマ定義.md) を参照してください。

本スキーマでは Enemy に必要な **ターゲット選択・戦闘AI・ドロップ** を定義します。

---

## スキーマ定義

### ai.targeting（ターゲット選択）

| キー                          | 型      | 必須 | デフォルト          | 説明                             |
|:----------------------------|:-------|:--:|:---------------|:-------------------------------|
| `ai.targeting.strategy`     | String | ○  | -              | ターゲット選択方式（後述 `TargetStrategy`） |
| `ai.targeting.aggroRange`   | Double | ○  | -              | 敵対検知範囲（ブロック単位）                 |
| `ai.targeting.deaggroRange` | Double | ×  | aggroRange × 2 | 敵対解除距離（ブロック単位）                 |
| `ai.targeting.leashRange`   | Double | ×  | 30.0           | スポーン地点からの最大追跡距離。超えるとリセット       |

#### TargetStrategy
- `NEAREST` : 最も近いプレイヤーをターゲット
- `HIGHEST_THREAT` : ヘイト（脅威値）が最も高いプレイヤーをターゲット
- `RANDOM` : 範囲内のプレイヤーからランダムに選択
- `LOWEST_HP` : HPが最も低いプレイヤーを優先

### ai.combat（戦闘行動）

| キー                              | 型            | 必須 | デフォルト     | 説明                                                  |
|:--------------------------------|:-------------|:--:|:----------|:----------------------------------------------------|
| `ai.combat.style`               | String       | ○  | -         | 戦闘スタイル（後述 `CombatStyle`）                            |
| `ai.combat.preferredRange`      | Double       | ×  | 1.5       | 戦闘時の理想距離（ブロック単位）。`MELEE` は接近、`RANGED`/`MAGIC` は距離確保 |
| `ai.combat.attackIntervalTicks` | Long         | ×  | 20        | 通常攻撃の間隔（tick）。20 tick = 1 秒                         |
| `ai.combat.skills`              | List<String> | ×  | emptyList | 使用するスキルのID一覧（※参照値。例: `ref: skill:fire_bolt`）        |

#### CombatStyle
- `MELEE` : 近接戦闘。ターゲットに接近して攻撃
- `RANGED` : 遠距離戦闘。弓など物理遠距離攻撃。距離を保ちつつ攻撃
- `MAGIC` : 魔法戦闘。魔法系遠距離攻撃。距離を保ちつつ攻撃

### drops（ドロップ設定）

Mob撃破時のドロップを定義します。

| キー                           | 型       | 必須 | デフォルト     | 説明                                                           |
|:-----------------------------|:--------|:--:|:----------|:-------------------------------------------------------------|
| `drops.exp`                  | Integer | ○  | -         | 撃破時に獲得する経験値                                                  |
| `drops.money`                | Map     | ×  | Null      | 撃破時に獲得するお金（後述）                                               |
| `drops.money.min`            | Integer | ○  | -         | 最小値                                                          |
| `drops.money.max`            | Integer | ○  | -         | 最大値                                                          |
| `drops.items[]`              | List    | ×  | emptyList | ドロップアイテムリスト（後述）                                              |
| `drops.items[].itemId`       | String  | ○  | -         | ドロップするアイテムのID（※参照値。例: `ref: item:iron_ingot`）                |
| `drops.items[].rate`         | Double  | ○  | -         | ドロップ確率（0.00〜100.00）。小数点以下の精度あり                               |
| `drops.items[].amount`       | String  | ×  | 1         | ドロップ数量。固定値または範囲（例: `1` / `1~3`）                              |
| `drops.items[].luckAffected` | Boolean | ×  | true      | `true` の場合、幸運・確率アップ系効果の影響を受ける                                |
| `drops.items[].hidden`       | Boolean | ×  | false     | `true` の場合、敵の情報ブック（図鑑）にドロップアイテムとして表示されない（隠しドロップ）             |
| `drops.lootTable`            | String  | ×  | Null      | 既存の LootTable を参照する場合（※参照値。例: `ref: loot_table:common_drop`） |

> `drops.items[]` と `drops.lootTable` は併用可能です。両方指定された場合、双方の抽選がそれぞれ実行されます。

---

## YAML 例

### 例1: 近接型エネミー

```yaml
schemaVersion: 1
id: goblin_warrior
type: MOB
category: ENEMY
name: "&cゴブリンウォリアー"
level: 5
entityType: ZOMBIE
icon: ZOMBIE_HEAD
lore:
  - "&7森に生息する好戦的なゴブリン。"
  - "&7粗末な武器を持って襲い掛かる。"
tags:
  - humanoid
  - goblin

equipment:
  mainHand:
    ref: item:rusty_sword
  helmet:
    ref: item:leather_cap

baseStats:
  - status: MAX_HEALTH
    value: 150
  - status: ATTACK
    value: 18
  - status: DEFENSE
    value: 5
  - status: MOVEMENT_SPEED
    value: 0.12



    behavior: WANDER
    wanderRadius: 8
    speed: 0.8
  targeting:
    strategy: NEAREST
    aggroRange: 12
    deaggroRange: 25
    leashRange: 30
  combat:
    style: MELEE
    preferredRange: 1.5
    attackIntervalTicks: 25
    skills:
      - ref: skill:slash

drops:
  exp: 30
  money:
    min: 5
    max: 15
  items:
    - itemId:
        ref: item:goblin_ear
      rate: 80.0
      amount: 1
      luckAffected: true
      hidden: false
    - itemId:
        ref: item:rusty_sword
      rate: 5.50
      amount: 1
      luckAffected: true
      hidden: false
    - itemId:
        ref: item:goblin_kings_seal
      rate: 0.05
      amount: 1
      luckAffected: false
      hidden: true
```

### 例2: 遠距離型エネミー（魔法）

```yaml
schemaVersion: 1
id: skeleton_mage
type: MOB
category: ENEMY
name: "&5スケルトンメイジ"
level: 12
entityType: SKELETON
icon: SKELETON_SKULL
lore:
  - "&7暗黒魔法を操るスケルトン。"
  - "&7距離を取りながら魔法で攻撃する。"
tags:
  - undead
  - magic

equipment:
  mainHand:
    ref: item:dark_staff

baseStats:
  - status: MAX_HEALTH
    value: 100
  - status: ATTACK
    value: 35
  - status: DEFENSE
    value: 3
  - status: MOVEMENT_SPEED
    value: 0.10

ai:
  idle:
    behavior: STATIONARY
  targeting:
    strategy: LOWEST_HP
    aggroRange: 18
  combat:
    style: MAGIC
    preferredRange: 12
    attackIntervalTicks: 40
    skills:
      - ref: skill:dark_bolt
      - ref: skill:shadow_curse

drops:
  exp: 65
  money:
    min: 15
    max: 40
  items:
    - itemId:
        ref: item:bone_fragment
      rate: 70.0
      amount: 1~3
    - itemId:
        ref: item:dark_essence
      rate: 12.0
      amount: 1
      luckAffected: true
```

