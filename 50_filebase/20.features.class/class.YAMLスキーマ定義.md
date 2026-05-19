**# Class YAML スキーマ定義

Class（職業）のスキーマ定義。

本定義は、プレイヤーの職業テンプレート（成長方針・初期ステータス・進行解放要件）を管理するためのものです。
ステータスの種別はプラグイン側で定義されるため、本スキーマではステータス名（`status`）と値（`value`）のペアのみを指定します。

> **StatusType について**: 使用可能なステータス名の一覧は [`file/00.meta/StatusType.md`](../00.meta/StatusType.md) を参照してください。

## スキーマ定義

| キー                         | 型            | 必須 | デフォルト     | 説明                                               |
|:---------------------------|:-------------|:--:|:----------|:-------------------------------------------------|
| `schemaVersion`            | Integer      | ○  | -         | スキーマのバージョン（2026-03-31時点は `1`）                    |
| `id`                       | String       | ○  | -         | classのテンプレートID（例: `warrior`）                     |
| `type`                     | String       | ○  | -         | 種別（CLASS(cls)）                                   |
| `name`                     | String       | ○  | -         | ゲーム内に表示される職業名                                    |
| `description`              | String       | ×  | Null      | 職業説明文                                            |
| `icon`                     | String       | ×  | Null      | 表示アイコン（任意。表現は実装側に委ねる）                            |
| `role`                     | String       | ○  | -         | 職業ロール（後述）                                        |
| `unlockLevel`              | Integer      | ×  | 1         | 解放に必要な最低プレイヤーレベル                                 |
| `unlockClassLevel[]`       | List         | ×  | -         | 解放に必要な素材クラスとレベルを指定                               |
| `unlockClassLevel[].class` | String       | ×  | -         | 解放に必要な素材クラスを指定                                   |
| `unlockClassLevel[].level` | Integer      | ×  | -         | 解放に必要な素材クラスのレベルを指定                               |
| `baseStats[]`              | List         | ○  | -         | 初期ステータスのリスト（後述）                                  |
| `baseStats[].status`       | String       | ○  | -         | ステータス名（`StatusType`。プラグイン側で定義。例: `MAX_HEALTH`）   |
| `baseStats[].value`        | Double       | ○  | -         | 初期値                                              |
| `growthPerLevel[]`         | List         | ×  | emptyList | レベルアップ時の成長量リスト（後述）                               |
| `growthPerLevel[].status`  | String       | ○  | -         | ステータス名（`StatusType`。`baseStats` と同様）             |
| `growthPerLevel[].value`   | Double       | ○  | -         | 1レベルあたりの増加量                                      |
| `expRate`                  | Integer      | ×  | 100       | 必要経験値の倍率指標（基準値 `100`。値が大きいほど必要経験値が増え、レベルが上がりにくい） |
| `starterSkills`            | List<String> | ×  | emptyList | 初期習得スキルID一覧（参照値）                                 |
| `levelSkills[]`            | List         | ×  | emptyList | レベル到達時に習得するスキルのリスト                               |
| `levelSkills[].level`      | Integer      | ○  | -         | スキルを習得するレベル                                      |
| `levelSkills[].skill`      | String       | ○  | -         | 習得するスキルID（参照値。例: `ref: skill:shield_bash`）       |
| `tags`                     | List<String> | ×  | emptyList | 検索・分類用タグ（例: `melee`, `tank`）                     |

### role
以下のいずれかの値を指定します。
- TANK
- DEALER
- HEALER
- SUPPORT

### baseStats[].status / growthPerLevel[].status（StatusType）

プラグイン側で定義されるステータス名を指定します。使用可能な値の一覧は [`file/00.meta/StatusType.md`](../00.meta/StatusType.md) を参照してください。

### 参照（ref）
他DBからclassを参照する場合は `class:` prefix を使用します（aliases: `cls`）。

## YAML 例

```yaml
schemaVersion: 1
id: warrior
type: CLASS
name: "&c戦士"
description: "&7近接戦闘を得意とする前衛職。"
role: TANK
unlockLevel: 1
unlockClassLevel:
  - class: fighter
    level: 10
  - class: scout
    level: 10
baseStats:
  - status: MAX_HEALTH
    value: 120
  - status: MAX_MANA
    value: 50
  - status: STRENGTH
    value: 14
  - status: VITALITY
    value: 12
  - status: AGILITY
    value: 8
  - status: ATTACK
    value: 10
  - status: DEFENSE
    value: 10
  - status: MOVEMENT_SPEED
    value: 100
growthPerLevel:
  - status: MAX_HEALTH
    value: 8
  - status: MAX_MANA
    value: 2
  - status: STRENGTH
    value: 2
  - status: VITALITY
    value: 1.5
  - status: ATTACK
    value: 1
  - status: DEFENSE
    value: 1
expRate: 130
starterSkills:
  - ref: skill:slash
levelSkills:
  - level: 10
    skill: ref: skill:shield_bash
  - level: 20
    skill: ref: skill:taunt
  - level: 30
    skill: ref: skill:iron_wall
tags:
  - melee
  - front
```**
