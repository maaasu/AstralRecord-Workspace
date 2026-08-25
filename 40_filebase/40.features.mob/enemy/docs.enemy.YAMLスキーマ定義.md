# Enemy YAML スキーマ定義

Enemy（通常エネミー）の固有フィールド定義。

共通フィールド（`schemaVersion`, `id`, `type`, `category`, `name`, `entityType`, `baseStats`, `equipment`, `ai.idle` 等）は
[docs.mob.YAMLスキーマ定義.md](../docs.mob.YAMLスキーマ定義.md) を参照してください。

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
| `ai.targeting.retaliateOnly` | Boolean | × | false | 攻撃を受けた後のみターゲット選定を行うか |

#### TargetStrategy
- `NEAREST` : 最も近いプレイヤーをターゲット
- `HIGHEST_THREAT` : ヘイト（脅威値）が最も高いプレイヤーをターゲット
- `RANDOM` : 範囲内のプレイヤーからランダムに選択
- `LOWEST_HP` : HPが最も低いプレイヤーを優先

`retaliateOnly: true` を指定した場合、通常時はプレイヤーを自動検知せず、`ai.idle.behavior: WANDER` の設定に従ってスポーン地点周辺を徘徊します。攻撃を受けて反撃対象が設定された後にのみ、その攻撃者をターゲットにします。

### ai.combat（戦闘行動）

| キー                              | 型            | 必須 | デフォルト     | 説明                                                  |
|:--------------------------------|:-------------|:--:|:----------|:----------------------------------------------------|
| `ai.combat.style`               | String       | ○  | -         | 戦闘スタイル（後述 `CombatStyle`）                            |
| `ai.combat.preferredRange`      | Double       | ×  | 1.0       | 戦闘時の理想距離（ブロック単位）。`MELEE` は接近、`RANGED`/`MAGIC` は距離確保 |
| `ai.combat.normalAttack` | Map | × | - | 通常攻撃を持つ Mob だけが定義する。省略時は通常攻撃なし |
| `ai.combat.normalAttack.range` | Double | ○ | - | 通常攻撃が届く距離（ブロック単位） |
| `ai.combat.normalAttack.intervalTicks` | Long | ○ | - | 通常攻撃の間隔。20 tick = 1 秒 |
| `ai.combat.skills[]` | List | × | empty | Mob 専用スキルの発動順。ID ごとに Java の executor が存在する |
| `ai.combat.skills[].id` | String | ○ | - | `mob_` 接頭辞の Mob 専用スキル ID。player の skill master / skill gem には登録しない |
| `ai.combat.skills[].activationRange` | Double | × | executor 既定値 | 発動を試みる距離 |
| `ai.combat.skills[].cooldownTicks` | Long | × | executor 既定値 | 同じスキルを次に開始できるまでの tick |
| `ai.combat.skills[].castTimeTicks` | Long | × | executor 既定値 | 詠唱時間。0 は即時発動、1 以上は頭上の詠唱表示を行う |
| `ai.combat.skills[].params` | Map<String, Double> | × | empty | executor 固有の数値パラメーター。キー・必須性・範囲は executor の JavaDoc を正とする。10 個まで |

`attackIntervalTicks` を直接置く旧形式は読み込み互換のためだけに残ります。新規・更新マスターでは
`normalAttack.range` と `normalAttack.intervalTicks` を使用してください。

`skills` の文字列 ID 配列（例: `skills: [mob_example]`）も既存データ互換のため読み込みますが、
新規・更新マスターでは timing と `params` を表現できる object 形式だけを使用してください。

`normalAttack` と `skills` は併用できます。例えば、近接の通常攻撃を行いつつ、個別クールダウンの
詠唱スキルをローテーションできます。スキルだけで攻撃する Mob は `normalAttack` を定義しません。

#### CombatStyle
- `MELEE` : 近接戦闘。ターゲットに接近して攻撃
- `RANGED` : 遠距離戦闘。弓など物理遠距離攻撃。距離を保ちつつ攻撃
- `MAGIC` : 魔法戦闘。魔法系遠距離攻撃。距離を保ちつつ攻撃

### drops（ドロップ設定）

Mob撃破時の報酬を定義します。`drops` 自体は任意で、省略時は経験値・通貨・アイテムをすべて付与しません。経験値だけを残す場合は `drops.exp` だけを定義します。

| キー                           | 型       | 必須 | デフォルト     | 説明                                                           |
|:-----------------------------|:--------|:--:|:----------|:-------------------------------------------------------------|
| `drops.exp`                  | Integer | ○  | -         | `drops` を定義した場合に必須。撃破時に獲得する経験値                                  |
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
id: midgard_grassboar
type: MOB
category: ENEMY
name: "&aミズガルズ・グラスボア"
level: 2
entityType: PIG
icon: PIG
lore:
  - "&7風待ち草原の外れをうろつく流れ者。"
  - "&7木道の残材を武器にして襲い掛かる。"
tags:
  - humanoid
  - windwait

equipment:
  mainHand: IRON_SWORD

baseStats:
  - status: MAX_HEALTH
    value: 55
  - status: ATTACK
    value: 7
  - status: DEFENSE
    value: 1
  - status: MOVEMENT_SPEED
    value: 102

ai:
  idle:
    behavior: WANDER
    wanderRadius: 8
    speed: 0.8
  targeting:
    strategy: NEAREST
    aggroRange: 14
    deaggroRange: 22
    leashRange: 28
  combat:
    style: MELEE
    preferredRange: 2.2
    normalAttack:
      range: 2.2
      intervalTicks: 24

drops:
  exp: 16
  money:
    min: 1
    max: 4
  items:
    - itemId:
        ref: item:grassboar_pelt
      rate: 18.0
      amount: 1
      luckAffected: true
      hidden: false
    - itemId:
        ref: item:rune_bone
      rate: 4.5
      amount: 1
      luckAffected: false
      hidden: false
  lootTable:
    ref: loot_table:windwait_field_table
```

### 例2: 遠距離型エネミー（魔法）

```yaml
schemaVersion: 1
id: skeleton_mage
type: MOB
category: ENEMY
name: "&0スケルトンメイジ"
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
  mainHand: BLAZE_ROD

baseStats:
  - status: MAX_HEALTH
    value: 180
  - status: ATTACK
    value: 30
  - status: DEFENSE
    value: 3
  - status: MAGIC_DEFENSE
    value: 8
  - status: MOVEMENT_SPEED
    value: 90

ai:
  idle:
    behavior: STATIONARY
  targeting:
    strategy: LOWEST_HP
    aggroRange: 18
  combat:
    style: MAGIC
    preferredRange: 12
    skills:
      - id: mob_skeleton_arcane_bolt
        activationRange: 14
        cooldownTicks: 40
        castTimeTicks: 16
        params:
          damageRatio: 0.8

drops:
  exp: 60
  money:
    min: 10
    max: 40
  items:
    - itemId:
        ref: item:midgard_iron_shard
      rate: 70.0
      amount: 1~3
    - itemId:
        ref: item:dark_essence
      rate: 12.0
      amount: 1
      luckAffected: true
```
