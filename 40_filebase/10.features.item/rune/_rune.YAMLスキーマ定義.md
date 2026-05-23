# RUNE (ルーン) YAML スキーマ定義

装備のスロットに嵌め込むことでステータス補正やスキルを付与する「ルーン」アイテムを表現します。
通常のアイテム共通項目（`schemaVersion` / `id` / `category` / `name` など）は `item.YAMLスキーマ定義.md` を参照し、本書では Rune 固有項目のみ定義します。

> **StatusType について**: `status` フィールドに使用できるステータス名の一覧は [`file/00.meta/StatusType.md`](../../00.meta/StatusType.md) を参照してください。

## スキーマ定義

| キー                             | 型            | 必須 | デフォルト | 説明                                                                              |
|:-------------------------------|:-------------|:--:|:------|:--------------------------------------------------------------------------------|
| `rune.targetSlots[]`           | List<String> | ○  | -     | このルーンを装備できる装備スロット種別のリスト（`WEAPON` / `HEAD` / `CHEST` など）。`ANY` を指定すると全スロット対応。    |
| `rune.requiredEnhanceLevel`    | Integer      | ×  | 0     | このルーンをセットするために必要な装備の強化（`enhance`）レベルの最小値。`0` で制限なし。                             |
| `rune.stats[]`                 | List         | ×  | -     | ルーン装着中に装備へ付与されるステータス補正のリスト。                                                     |
| `rune.stats[].status`          | String       | ×  | -     | 対象ステータス（`StatusType`）。例: `ATTACK` / `DEFENSE` / `CRITICAL_RATE`。                |
| `rune.stats[].type`            | String       | ×  | -     | 補正方式（`FLAT` / `SCALAR`）。`FLAT` は加算、`SCALAR` は乗算係数。                              |
| `rune.stats[].value`           | String       | ×  | -     | 補正値。固定値または範囲（例: `5` / `3~8`）。`SCALAR` の場合は `0.10 = +10%`。                       |
| `rune.stats[].random[].min`    | String       | ×  | -     | 固定値（例: `-10`） -10%。「`equipment.stats.random: -10~10`」も可能。装備作成時にステータスをランダムに設定する。 |
| `rune.stats[].random[].max`    | String       | ×  | -     | 固定値（例: `10`）  +10%。「`equipment.stats.random: -10~10`」も可能。装備作成時にステータスをランダムに設定する。 |
| `rune.skills[]`                | List<String> | ×  | -     | ルーン装着中に装備へ付与されるスキルIDのリスト。※参照値                                                   |

### rune.targetSlots[]
以下のいずれかの値を指定します（複数指定可）。

- `ANY` : 全スロット対応
- `WEAPON`
- `SUBWEAPON`
- `HEAD`
- `CHEST`
- `LEGS`
- `FEET`
- `ACCESSORY`
- `TOOL`

### rune.stats[].type
- `FLAT` : 定数加算
- `SCALAR` : 乗算（ベース値に対して）

### rune.requiredEnhanceLevel について

装備側の `enhance` レベルが `requiredEnhanceLevel` 以上でなければ、このルーンをセットできません。
`0` または未指定の場合は強化レベルに関係なくセット可能です。

### 参照（ref）
スキルを参照する場合は `skill:` prefix を使用します（aliases: `sk`）。

- 例: `ref: skill:fire_boost`

## YAML 例

### 例1: 基本的なルーン（ステータス補正のみ）

```yaml
schemaVersion: 1
id: rune_attack_small
category: RUNE
name: "&c攻撃のルーン【小】"
icon: BRICK
rarity: COMMON
lore:
  - "&7装備に嵌め込むことで攻撃力を高める。"

maxStack: 1
rune:
  targetSlots:
    - WEAPON
  stats:
    - status: ATTACK
      type: FLAT
      value: 5
```

### 例2: 複数スロット対応・強化レベル条件付きルーン

```yaml
schemaVersion: 1
id: rune_defense_medium
category: RUNE
name: "&a防御のルーン【中】"
icon: NETHER_BRICK
rarity: UNCOMMON
lore:
  - "&7防御力と魔法防御力を高める中級ルーン。"
  - "&e強化レベル3以上の装備にセット可能。"

maxStack: 1
rune:
  targetSlots:
    - HEAD
    - CHEST
    - LEGS
    - FEET
  requiredEnhanceLevel: 3
  stats:
    - status: DEFENSE
      type: FLAT
      value: 10
    - status: MAGIC_DEFENSE
      type: FLAT
      value: 8
```

### 例3: スキル付与ルーン（強化レベル条件付き）

```yaml
schemaVersion: 1
id: rune_fire_blade
category: RUNE
name: "&6炎刃のルーン"
icon: BLAZE_ROD
rarity: RARE
lore:
  - "&7武器に炎の力を宿すルーン。"
  - "&e強化レベル5以上の武器にセット可能。"

maxStack: 1
rune:
  targetSlots:
    - WEAPON
  requiredEnhanceLevel: 5
  stats:
    - status: ATTACK
      type: FLAT
      value: 15
    - status: CRITICAL_RATE
      type: FLAT
      value: 0.05
  skills:
    - ref: skill:fire_boost
```

### 例4: 全スロット対応ルーン（スカラー補正）

```yaml
schemaVersion: 1
id: rune_exp_boost
category: RUNE
name: "&b経験値のルーン"
icon: EXPERIENCE_BOTTLE
rarity: UNCOMMON
lore:
  - "&7どの装備にも嵌め込める汎用ルーン。"
  - "&7獲得経験値量が増加する。"

maxStack: 1
rune:
  targetSlots:
    - ANY
  stats:
    - status: EXP_GAIN
      type: SCALAR
      value: 0.10
```
