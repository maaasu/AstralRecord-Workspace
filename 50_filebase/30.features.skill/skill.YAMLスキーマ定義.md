# Skill YAML スキーマ定義

Skill（スキル）のスキーマ定義。

本定義は、プレイヤーやMobが使用するアクティブ／パッシブスキルのテンプレートを管理するためのものです。

> **StatusType について**: `status` フィールドに使用できるステータス名の一覧は [`file/00.meta/StatusType.md`](../00.meta/StatusType.md) を参照してください。

## 設計方針

マスタデータ（YAML）とプラグイン実装の責務を以下のように分離します。

| 責務                             | 定義場所                     |
|--------------------------------|--------------------------|
| スキルシステム共通の制御情報（クールダウン・MP消費など）  | マスタデータ（YAML）             |
| プラグイン実装クラスへの紐付け                | `implementationId` フィールド |
| スキル固有の演出・挙動パラメータ（パーティクル色・弾速など） | `params` フィールド（自由Map）    |
| エフェクト・パーティクルの描画ロジック・当たり判定      | プラグイン側の実装                |

## スキーマ定義

| キー                           | 型            | 必須 | デフォルト     | 説明                                                        |
|:-----------------------------|:-------------|:--:|:----------|:----------------------------------------------------------|
| `schemaVersion`              | Integer      | ○  | -         | スキーマのバージョン（2026-03-31時点は `1`）                             |
| `id`                         | String       | ○  | -         | スキルのテンプレートID（例: `fireball_a`）                             |
| `type`                       | String       | ○  | -         | 種別（SKILL(sk)）                                             |
| `implementationId`           | String       | ○  | -         | プラグイン側の実装クラスに紐付けるキー（例: `fireball`）。同一実装クラスを複数スキルで共有する際に使用 |
| `name`                       | String       | ○  | -         | ゲーム内に表示されるスキル名                                            |
| `description`                | String       | ×  | Null      | スキル説明文                                                    |
| `icon`                       | String       | ×  | Null      | 表示用アイコン（任意。表現は実装側に委ねる）                                    |
| `lore`                       | List<String> | ×  | emptyList | 説明文（§ または & の色コード利用可能）                                    |
| `cooldownTicks`              | Long         | ×  | 0         | クールダウン時間（tick）。20 tick = 1 秒。`0` の場合はクールダウンなし             |
| `manaCost`                   | Double       | ×  | 0         | 使用時のMP消費量                                                 |
| `castTimeTicks`              | Long         | ×  | 0         | 詠唱時間（tick）。`0` の場合は即時発動                                   |
| `requiredLevel`              | Integer      | ×  | 1         | 習得に必要なプレイヤーレベル                                            |
| `onCast`                     | Map          | ×  | Null      | 発動時の演出（後述）                                                |
| `onCast.sound`               | String       | ×  | Null      | 発動時に流れるサウンド（SoundKey想定。例: `entity.player.attack.sweep`）   |
| `params`                     | Map          | ×  | emptyMap  | プラグイン実装が読み取るカスタムパラメータ（後述）。スキーマ検証の対象外                      |
| `tags`                       | List<String> | ×  | emptyList | 検索・分類用タグ（例: `melee`, `aoe`, `fire`）                       |

### params
プラグイン実装クラスが読み取る自由形式のパラメータMap（`Map<String, Any>`）。  
スキーマ検証の対象外であり、`implementationId` に対応するプラグイン実装ごとに自由に定義できます。  
マスタデータ管理者とプラグイン開発者の間で使用するキー・値の型を合意した上で記述してください。

**例（ファイアボール系スキルの場合）:**

| キー例               | 型例     | 説明例                          |
|-------------------|--------|------------------------------|
| `flameColor`      | String | パーティクルの炎色（`RED` / `BLUE` など） |
| `explosionRadius` | Double | 爆発半径（ブロック単位）                 |
| `projectileSpeed` | Double | 弾速                           |

### 参照（ref）
他DBからskillを参照する場合は `skill:` prefix を使用します（aliases: `sk`）。

## YAML 例

### 例1: 近接攻撃スキル

```yaml
schemaVersion: 1
id: slash
type: SKILL
implementationId: slash
name: "&fスラッシュ"
description: "&7前方の敵に斬撃を与える基本攻撃スキル。"
icon: IRON_SWORD
cooldownTicks: 40
manaCost: 5
castTimeTicks: 0
requiredLevel: 1
onCast:
  sound: entity.player.attack.sweep
params:
  damage: 20
  scalingStatus: ATTACK
  scalingFactor: 1.2
tags:
  - melee
  - physical
```

### 例2: 回復スキル

```yaml
schemaVersion: 1
id: heal_light
type: SKILL
implementationId: heal
name: "&aヒールライト"
description: "&7味方一人のHPを回復する。"
icon: GOLDEN_APPLE
cooldownTicks: 200
manaCost: 30
castTimeTicks: 20
requiredLevel: 5
onCast:
  sound: block.amethyst_block.chime
params:
  healStatus: HP
  healValue: 0.15
  healIsPercent: true
tags:
  - heal
  - magic
```

### 例3: バフ付きAoEスキル

```yaml
schemaVersion: 1
id: war_cry
type: SKILL
implementationId: war_cry
name: "&c雄叫び"
description: "&7味方全員の攻撃力を上昇させる。"
icon: GOAT_HORN
cooldownTicks: 600
manaCost: 40
castTimeTicks: 10
requiredLevel: 10
onCast:
  sound: entity.ender_dragon.growl
params:
  buffId: "ref: buff:battle_focus"
tags:
  - support
  - aoe
  - buff
```

### 例4: ファイアボール（同一実装クラスを params で差別化）

```yaml
# ファイアボールA（クールタイム短め・赤・低威力）
schemaVersion: 1
id: fireball_a
type: SKILL
implementationId: fireball
name: "&cファイアボールA"
description: "&7素早く放つ小さな火球。"
icon: FIRE_CHARGE
cooldownTicks: 20
manaCost: 10
castTimeTicks: 0
requiredLevel: 1
onCast:
  sound: entity.blaze.shoot
params:
  flameColor: RED
  damage: 30
  scalingStatus: ATTACK
  scalingFactor: 1.0
  explosionRadius: 2.0
  projectileSpeed: 1.5
tags:
  - fire
  - magic
```

```yaml
# ファイアボールB（クールタイム長め・青・高威力）
schemaVersion: 1
id: fireball_b
type: SKILL
implementationId: fireball
name: "&9ファイアボールB"
description: "&7強大な蒼炎の火球。詠唱に時間がかかる。"
icon: FIRE_CHARGE
cooldownTicks: 100
manaCost: 50
castTimeTicks: 40
requiredLevel: 10
onCast:
  sound: entity.blaze.shoot
params:
  flameColor: BLUE
  damage: 80
  scalingStatus: ATTACK
  scalingFactor: 2.0
  explosionRadius: 4.0
  projectileSpeed: 2.0
tags:
  - fire
  - magic
```

> **プラグイン側の実装イメージ:**  
> `skillRegistry.register("fireball", FireballSkill())` の1行で `fireball_a` / `fireball_b` 両方に対応。  
> `FireballSkill` は `context.skillData.params["flameColor"]` などで `params` を読み取り、演出・当たり判定を制御する。
