# Boss YAML スキーマ定義

Boss（ボスMob）の固有フィールド定義。

共通フィールド（`schemaVersion`, `id`, `type`, `category`, `name`, `entityType`, `baseStats`, `equipment`, `ai.idle` 等）は
[docs.mob.YAMLスキーマ定義.md](../docs.mob.YAMLスキーマ定義.md) を参照してください。

ボスの基本的な戦闘AIとドロップ構造は Enemy と同一です。
固有のフェーズ遷移・スキルギミック・演出はプラグイン側で実装するため、本スキーマでは宣言しません。

---

## スキーマ定義

### ai.targeting（ターゲット選択）

Enemy と同一仕様です。`drops` 自体は任意で、省略時は経験値・通貨・アイテムをすべて付与しません。経験値だけを残す場合は `drops.exp` だけを定義します。

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

Enemy と同一仕様です。ボス固有のスキルローテーション、倍率、発動時演出、フェーズ遷移は Mob 専用 executor とサービスで制御します。

| キー                              | 型            | 必須 | デフォルト     | 説明                                                  |
|:--------------------------------|:-------------|:--:|:----------|:----------------------------------------------------|
| `ai.combat.style`               | String       | ○  | -         | 戦闘スタイル（後述 `CombatStyle`）                            |
| `ai.combat.preferredRange`      | Double       | ×  | 1.0       | 戦闘時の理想距離（ブロック単位）。`MELEE` は接近、`RANGED`/`MAGIC` は距離確保 |
| `ai.combat.normalAttack` | Map | × | - | 通常攻撃を持つ場合の距離・間隔。省略時は直接通常攻撃なし |
| `ai.combat.skills[]` | List | × | empty | `mob_` ID の Mob 専用スキル。詳細は Enemy schema を参照 |

#### CombatStyle
- `MELEE` : 近接戦闘。ターゲットに接近して攻撃
- `RANGED` : 遠距離戦闘。弓など物理遠距離攻撃。距離を保ちつつ攻撃
- `MAGIC` : 魔法戦闘。魔法系遠距離攻撃。距離を保ちつつ攻撃

### drops（ドロップ設定）

Enemy と同一仕様です。

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

### ボス固有の補足事項

- **フェーズ遷移** : HP 閾値によるフェーズ切り替えはプラグイン側で実装。本スキーマでは定義しない。
- **専用ギミック** : 戦闘エリアの制限・特殊オブジェクト・QTEなどはプラグイン側で実装。
- **スキルローテーション** : Mob master には列挙せず、プラグイン側の専用 executor が使用順序・条件分岐・効果を制御。

## challenge

ボス挑戦を有効にする場合、BOSS マスタに `challenge` を定義する。

| キー | 型 | 必須 | 説明 |
|:--|:--|:--:|:--|
| `challenge.fieldWorldId` | String | ○ | `WorldType.BOSS_FIELD` の WorldMasterData ID |
| `challenge.entryLocation.worldId` | String | ○ | 挑戦受付地点の WorldMasterData ID または Bukkit world 名 |
| `challenge.entryLocation.x/y/z` | Double | ○ | 挑戦受付地点 |
| `challenge.entryLocation.yaw/pitch` | Double | × | 受付地点の向き |
| `challenge.entryRadius` | Double | × | スニーク受付半径。未指定時 3.0 |
| `challenge.playerSpawnLocation.x/y/z` | Double | ○ | フィールド内の参加者転送先 |
| `challenge.bossSpawnLocation.x/y/z` | Double | ○ | フィールド内のボススポーン地点 |
| `challenge.partyMin` | Integer | × | 最小参加人数。未指定時 1 |
| `challenge.partyMax` | Integer | × | 最大参加人数。未指定時 6 |
| `challenge.timeLimitSeconds` | Long | × | 制限時間。未指定時 600 |
| `challenge.deathLimit` | Integer | × | 0 以上。設定回数までは死亡可能で、次の死亡時に終了するパーティー共有死亡許容回数。未指定時 5 |
| `challenge.reviveDelaySeconds` | Long | × | 許容回数以内の死亡時にフィールドへ復帰するまでの秒数。未指定時 5 |
| `challenge.scaling.enabled` | Boolean | × | 参加人数補正の有効化 |
| `challenge.scaling.healthPerExtraPlayer` | Double | × | 2人目以降1人あたりの HP 増加率 (%) |
| `challenge.scaling.attackPerExtraPlayer` | Double | × | 2人目以降1人あたりの攻撃力増加率 (%)。ボスの与ダメージ倍率へ適用する |

---

## YAML 例

```yaml
schemaVersion: 1
id: twilight_colossus
type: MOB
category: BOSS
name: "&0&l暗黒竜ヴァルザード"
title: "&8―― 深淵の支配者 ――"
level: 80
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
  - status: MAGIC_DEFENSE
    value: 80
  - status: MOVEMENT_SPEED
    value: 100
  - status: CRITICAL_RATE
    value: 10.0
  - status: CRITICAL_DAMAGE
    value: 200.0

ai:
  idle:
    behavior: STATIONARY
  targeting:
    strategy: HIGHEST_THREAT
    aggroRange: 40
    deaggroRange: 60
    leashRange: 80
  combat:
    style: MAGIC
    preferredRange: 8
    normalAttack:
      range: 4
      intervalTicks: 40

drops:
  exp: 5000
  money:
    min: 500
    max: 1000
  items:
    - itemId:
        ref: item:dragon_scale
      rate: 100.0
      amount: 3~5
      luckAffected: true
      hidden: false
    - itemId:
        ref: item:varzard_soul_fragment
      rate: 1.00
      amount: 1
      luckAffected: true
      hidden: true
  lootTable:
    ref: loot_table:boss_common_drop
```
