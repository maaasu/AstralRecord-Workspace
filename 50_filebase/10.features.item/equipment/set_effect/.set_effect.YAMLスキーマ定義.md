# EQUIPMENT SET EFFECT（装備セット効果）YAML スキーマ定義

指定した数の同セット装備を装着したときに発動するセット効果を定義します。
Equipment 側の `setId` でこのファイルの `id` を参照することで、セット効果を装備と紐付けます。

> **StatusType について**: `stats` フィールドに使用できるステータス名の一覧は [`file/00.meta/StatusType.md`](../../00.meta/StatusType.md) を参照してください。

## スキーマ定義

| キー                        | 型            | 必須 | デフォルト | 説明                                                                        |
|:--------------------------|:-------------|:--:|:------|:--------------------------------------------------------------------------|
| `id`                      | String       | ○  | -     | セット効果ID。Equipment の `setId` から参照される（例: `iron_warrior_set`）。カテゴリ関係なく同ID禁止。 |
| `name`                    | String       | ○  | -     | セット名称。ゲーム内UIに表示される（例: `"&6鉄の戦士セット"`）。                                     |
| `pieces[]`                | List         | ○  | -     | セット装着数ごとの効果定義リスト。`count` の昇順で定義することを推奨。                                   |
| `pieces[].count`          | Integer      | ○  | -     | この効果が発動するために必要な同セット装備の装着数（例: `2` / `4`）。同一セット内で一意である必要がある。                |
| `pieces[].stats[]`        | List         | ×  | -     | この装着数で発動するステータス補正のリスト。                                                    |
| `pieces[].stats[].status` | String       | ×  | -     | 対象ステータス（`StatusType`）。例: `DEFENSE` / `MAX_HEALTH`。                        |
| `pieces[].stats[].type`   | String       | ×  | -     | 補正方式（`FLAT` / `SCALAR`）。`FLAT` は加算、`SCALAR` は乗算係数。                        |
| `pieces[].stats[].value`  | String       | ×  | -     | 補正値。固定値または範囲（例: `10` / `5~15`）。`SCALAR` の場合は `0.10 = +10%`。               |
| `pieces[].skills[]`       | List<String> | ×  | -     | この装着数で発動するスキルIDのリスト（参照値）。                                                 |

### pieces[].stats[].type
- `FLAT` : 定数加算
- `SCALAR`: 乗算（ベース値に対して）

### セット効果の発動ルール

- プレイヤーが同じ `setId` を持つ装備を同時に装着している数がカウントされます。
- `pieces[].count` の条件を満たす効果がすべて累積して適用されます（例: 2セット効果と4セット効果の両方の条件を満たす場合、両方が同時に有効になる）。
- 装着数が減り条件を下回った場合は、その効果は即時無効になります。
- `pieces[].skills[]` で指定したスキルは、パッシブスキルとして常時発動します。スキル自体の定義はスキルスキーマを参照してください。

### 参照（ref）
セット効果のスキルには `skill:` prefix を使用します（aliases: `sk`）。

- 例: `ref: skill:iron_will`

Equipment 側からセット効果を参照する場合は `setId` に ID を直接文字列で指定します。

- 例: `setId: iron_warrior_set`

## YAML 例

### 例1: 2段階のセット効果を持つ鉄の戦士セット

```yaml
id: iron_warrior_set
name: "&6鉄の戦士セット"
pieces:
  - count: 2
    stats:
      - status: DEFENSE
        type: FLAT
        value: 15
      - status: MAX_HEALTH
        type: FLAT
        value: 30
  - count: 4
    stats:
      - status: DEFENSE
        type: FLAT
        value: 40
      - status: MAX_HEALTH
        type: FLAT
        value: 80
      - status: HP_REGEN
        type: FLAT
        value: 5
    skills:
      - ref: skill:iron_will
```

### 例2: スキル付き3段階セット効果

```yaml
id: shadow_assassin_set
name: "&5影の暗殺者セット"
pieces:
  - count: 2
    stats:
      - status: CRITICAL_RATE
        type: FLAT
        value: 8
      - status: AGILITY
        type: FLAT
        value: 10
  - count: 3
    stats:
      - status: CRITICAL_DAMAGE
        type: FLAT
        value: 20
    skills:
      - ref: skill:shadow_step
  - count: 6
    stats:
      - status: ATTACK
        type: SCALAR
        value: 0.15
      - status: CRITICAL_RATE
        type: FLAT
        value: 12
    skills:
      - ref: skill:shadow_step
      - ref: skill:death_mark
```

---

### Equipment 側の記述例

同セット装備の `setId` に同じIDを指定することでセットを構成します。

```yaml
# v1.iron_helmet.yml
id: iron_helmet
category: equipment
# ...共通フィールド省略...
equipment:
  slot: HEAD
  setId: iron_warrior_set
  stats:
    - status: DEFENSE
      type: FLAT
      value: 8

---
# v1.iron_chestplate.yml（既存ファイルへの追記例）
equipment:
  slot: CHEST
  setId: iron_warrior_set
  stats:
    - status: DEFENSE
      type: FLAT
      value: 15
```
