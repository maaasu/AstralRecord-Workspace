**# Class YAML スキーマ定義

Class（職業）のスキーマ定義。

本定義は、プレイヤーの職業テンプレート（成長方針・初期ステータス・進行解放要件）を管理するためのものです。
ステータスの種別は共有カタログで定義されるため、本スキーマではステータスID（`status`）と値（`value`）のペアのみを指定します。

> **StatusType について**: 使用可能なステータスIDは、共有カタログ[`v1.status_types.yml`](../75.shared.status/v1.status_types.yml)を参照してください。

## スキーマ定義

| キー                         | 型            | 必須 | デフォルト     | 説明                                               |
|:---------------------------|:-------------|:--:|:----------|:-------------------------------------------------|
| `schemaVersion`            | Integer      | ○  | -         | スキーマのバージョン（2026-03-31時点は `1`）                    |
| `id`                       | String       | ○  | -         | classのテンプレートID（例: `warrior`）                     |
| `type`                     | String       | ○  | -         | 種別（CLASS(cls)）                                   |
| `name`                     | String       | ○  | -         | ゲーム内に表示される職業名                                    |
| `order`                    | Double       | ○  | -         | クラス一覧で表示する順序。値が小さいクラスから表示する                 |
| `shortName`                | String       | ○  | -         | クラスの短縮表示に使うASCII英大文字3文字の職業短縮名。色・装飾コードと前後空白を除いた表示文字をクラス間で一意にする |
| `description`              | String       | ×  | Null      | 職業説明文                                            |
| `icon`                     | String       | ×  | Null      | 表示アイコン（任意。表現は実装側に委ねる）                            |
| `role`                     | String       | ○  | -         | 職業ロール（後述）                                        |
| `maxLevel`                 | Integer      | ×  | `100`     | この職業のクラスレベル上限。`1` 以上を指定する              |
| `commandOnly`              | Boolean      | ×  | `false`   | `true` の職業は職業 GUI・通常転職では選択せず、管理コマンドだけで変更する |
| `unlockLevel`              | Integer      | ×  | 1         | 解放に必要な最低プレイヤーレベル                                 |
| `unlockClassLevel[]`       | List         | ×  | -         | 解放に必要な素材クラスとレベルを指定                               |
| `unlockClassLevel[].class` | String       | ×  | -         | 解放に必要な素材クラスを指定                                   |
| `unlockClassLevel[].level` | Integer      | ×  | -         | 解放に必要な素材クラスのレベルを指定                               |
| `baseStats[]`              | List         | ○  | -         | 現在クラスの初期ステータス補正のリスト（共有基礎値へ加算。後述）                                  |
| `baseStats[].status`       | String       | ○  | -         | 共有カタログのステータスID（例: `MAX_HEALTH`）   |
| `baseStats[].value`        | Double       | ○  | -         | クラスレベル1の補正値                                              |
| `growthPerLevel[]`         | List         | ×  | emptyList | クラスレベルアップ時の成長量リスト（後述）                               |
| `growthPerLevel[].status`  | String       | ○  | -         | ステータス名（`StatusType`。`baseStats` と同様）             |
| `growthPerLevel[].value`   | Double       | ○  | -         | クラスレベル1上昇あたりの増加量                                      |
| `expRate`                  | Integer      | ×  | 100       | 必要経験値の倍率指標（基準値 `100`。値が大きいほど必要経験値が増え、レベルが上がりにくい） |
| `usableSkills`             | List<String> | ×  | emptyList | 現在クラスで発動を許可するスキル ID。習得・所持は変更しない |
| `tags`                     | List<String> | ×  | emptyList | 共有タグカタログの`CLASS`対象ID（例: `melee`, `tank`）      |

### maxLevel / commandOnly

- `maxLevel` は職業ごとのクラスレベル上限で、省略時は `100` です。`1` 未満はプラグイン側で `1` に補正します。
- `commandOnly: true` の職業は職業 GUI・NPC 転職から選択できず、ADMIN の `/class change <classId>` だけで変更できます。

### shortName

- `shortName` はクラスの短縮表示名として保持する項目です。
- `&` 形式の色・装飾コードと前後空白を除いた表示文字を、ASCII の英大文字 `A-Z` だけで構成したちょうど3文字（正規表現 `^[A-Z]{3}$`）にします。
- 大文字・小文字を区別せず、ほかのクラスと同じ短縮名を定義してはいけません。
- タブのプレイヤーリストでは、色・装飾コードを反映した `shortName` を表示します。

### role
以下のいずれかの値を指定します。
- TANK
- DEALER
- HEALER
- SUPPORT

### baseStats[].status / growthPerLevel[].status（StatusType）

共有カタログで定義される不変ステータスIDを指定します。使用可能な値は[`v1.status_types.yml`](../75.shared.status/v1.status_types.yml)を参照してください。

`baseStats` と `growthPerLevel` は StatusService の共有基礎値へ加算するクラス補正です。現在クラスの補正値は、`baseStats + growthPerLevel × (classLevel - 1)` で計算します。`classLevel` はクラスごとの `maxLevel` の範囲へ補正し、定義されていないステータスの補正は `0.0` とします。

### 参照（ref）
他DBからclassを参照する場合は `class:` prefix を使用します（aliases: `cls`）。

### expRate
`expRate` はクラスレベルアップに必要なクラス経験値の倍率です。基準値は `100` で、値が大きいほど同じMob経験値でもクラスレベルが上がりにくくなります。

## YAML 例

```yaml
schemaVersion: 1
id: warrior
type: CLASS
name: "&c戦士"
shortName: "&cWAR"
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
    value: 00
  - status: STRENGTH
    value: 10
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
    value: 1.0
  - status: ATTACK
    value: 1
  - status: DEFENSE
    value: 1
expRate: 130
usableSkills:
  - skill:slash
  - skill:shield_bash
tags:
  - melee
  - front
```**
