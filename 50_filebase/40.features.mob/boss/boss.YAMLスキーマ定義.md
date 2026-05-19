# Boss YAML スキーマ定義

Boss（ボスMob）の固有フィールド定義。

共通フィールド（`schemaVersion`, `id`, `type`, `category`, `name`, `entityType`, `baseStats`, `equipment`, `ai.idle` 等）は
[mob.YAMLスキーマ定義.md](../mob.YAMLスキーマ定義.md) を参照してください。

ボスの基本的な戦闘AIとドロップ構造は Enemy と同一です。
固有のフェーズ遷移・スキルギミック・演出はプラグイン側で実装するため、本スキーマでは宣言しません。

---

## スキーマ定義

### ai.targeting（ターゲット選択）

Enemy と同一仕様です。

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

Enemy と同一仕様です。ボス固有のスキルローテーションやフェーズ遷移はプラグイン側で制御します。

| キー                              | 型            | 必須 | デフォルト     | 説明                                                  |
|:--------------------------------|:-------------|:--:|:----------|:----------------------------------------------------|
| `ai.combat.style`               | String       | ○  | -         | 戦闘スタイル（後述 `CombatStyle`）                            |
| `ai.combat.preferredRange`      | Double       | ×  | 1.5       | 戦闘時の理想距離（ブロック単位）。`MELEE` は接近、`RANGED`/`MAGIC` は距離確保 |
| `ai.combat.attackIntervalTicks` | Long         | ×  | 20        | 通常攻撃の間隔（tick）。20 tick = 1 秒                         |
| `ai.combat.skills`              | List<String> | ×  | emptyList | 使用するスキルのID一覧（※参照値。例: `ref: skill:dragon_breath`）    |

#### CombatStyle
- `MELEE` : 近接戦闘。ターゲットに接近して攻撃
- `RANGED` : 遠距離戦闘。弓など物理遠距離攻撃。距離を保ちつつ攻撃
- `MAGIC` : 魔法戦闘。魔法系遠距離攻撃。距離を保ちつつ攻撃

### drops（ドロップ設定）

Enemy と同一仕様です。

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

### ボス固有の補足事項

- **フェーズ遷移** : HP 閾値によるフェーズ切り替えはプラグイン側で実装。本スキーマでは定義しない。
- **専用ギミック** : 戦闘エリアの制限・特殊オブジェクト・QTEなどはプラグイン側で実装。
- **スキルローテーション** : `ai.combat.skills` にスキルIDを列挙し、使用順序・条件分岐はプラグイン側で制御。

---

## YAML 例

```yaml
schemaVersion: 1
id: dark_dragon
type: MOB
category: BOSS
name: "&4&l暗黒竜ヴァルザード"
title: "&8―― 深淵の支配者 ――"
level: 50
entityType: ENDER_DRAGON
icon: DRAGON_HEAD
lore:
  - "&7古より封印されし暗黒竜。"
  - "&7強大な力を持ち、挑戦者を待ち受ける。"
tags:
  - dragon
  - boss
  - fire

baseStats:
  - status: MAX_HEALTH
    value: 50000
  - status: ATTACK
    value: 200
  - status: DEFENSE
    value: 80
  - status: MOVEMENT_SPEED
    value: 0.15
  - status: CRITICAL_RATE
    value: 0.10
  - status: CRITICAL_DAMAGE
    value: 1.5

ai:
  idle:
    behavior: STATIONARY
  targeting:
    strategy: HIGHEST_THREAT
    aggroRange: 40
    deaggroRange: 60
    leashRange: 50
  combat:
    style: MAGIC
    preferredRange: 8
    attackIntervalTicks: 15
    skills:
      - ref: skill:dragon_breath
      - ref: skill:dark_nova
      - ref: skill:tail_sweep

drops:
  exp: 5000
  money:
    min: 500
    max: 1500
  items:
    - itemId:
        ref: item:dragon_scale
      rate: 100.0
      amount: 3~5
      luckAffected: false
      hidden: false
    - itemId:
        ref: item:dark_dragon_fang
      rate: 30.0
      amount: 1
      luckAffected: true
      hidden: false
    - itemId:
        ref: item:varzard_soul_fragment
      rate: 1.00
      amount: 1
      luckAffected: false
      hidden: true
  lootTable:
    ref: loot_table:boss_common_drop
```

