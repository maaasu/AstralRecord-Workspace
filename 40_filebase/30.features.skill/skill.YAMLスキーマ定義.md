# Skill YAML スキーマ定義

`40_filebase/30.features.skill/*.yml` に配置するスキル定義の schema です。

スキルの種別は plugin 側の `SkillExecutor.kind()` が正本です。
filebase 側では、パッシブスキルだった場合に `passive.bindRequired` でバインド必要可否を定義します。

## 項目

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `schemaVersion` | Integer | 必須 | - | 現在は `1` |
| `id` | String | 必須 | - | スキル ID |
| `type` | String | 必須 | - | `SKILL` |
| `implementationId` | String | 必須 | - | plugin 側実装 ID |
| `name` | String | 必須 | - | 表示名 |
| `description` | String | 任意 | `null` | 説明文 |
| `icon` | String | 任意 | `null` | Material 名 |
| `lore` | List<String> | 任意 | `[]` | 詳細表示 lore |
| `cooldownTicks` | Long | 任意 | `0` | クールダウン |
| `manaCost` | Double | 任意 | `0` | 消費 MP |
| `castTimeTicks` | Long | 任意 | `0` | 詠唱時間 |
| `requiredLevel` | Integer | 任意 | `1` | 必要レベル |
| `onCast` | Map | 任意 | `null` | 発動時演出設定 |
| `onCast.sound` | String | 任意 | `null` | 再生する sound key |
| `passive` | Map | 任意 | `null` | パッシブ設定 |
| `passive.bindRequired` | Boolean | 任意 | `true` | `true` の場合はバインド時のみ有効。`false` の場合は所持のみで常時有効 |
| `params` | Map<String, Any> | 任意 | `{}` | 実装側パラメータ |
| `tags` | List<String> | 任意 | `[]` | 任意タグ |

### normal_attack 共通 params

`implementationId: normal_attack` の Mob 攻撃では、詠唱開始時の予兆音として以下を指定できます。

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `castSound` | String | 任意 | `null` | 詠唱開始時に再生する sound key |
| `castSoundVolume` | Double | 任意 | `1.0` | 詠唱開始音の音量 |
| `castSoundPitch` | Double | 任意 | `1.0` | 詠唱開始音のピッチ |

## 補足

- 発動系スキルかパッシブスキルかは `implementationId` に対応する plugin 実装で決まります。
- `passive.bindRequired` は、plugin 実装がパッシブスキルの場合だけ意味を持ちます。
- `passive.bindRequired: false` のパッシブスキルは、スキル設定 GUI のバインド一覧には表示しません。
- `params` の解釈は各 executor に委ねます。

## 例

### 発動系スキル

```yaml
schemaVersion: 1
id: fire_boost
type: SKILL
implementationId: fire_boost
name: "&cファイアブースト"
description: "&7筋力を強化する発動系スキル。"
icon: BLAZE_POWDER
cooldownTicks: 0
manaCost: 0
castTimeTicks: 0
requiredLevel: 1
params:
  strengthDurationTicks: 400
  strengthAmplifier: 1
tags:
  - active
  - fire
```

### バインド必須のパッシブスキル

```yaml
schemaVersion: 1
id: iron_will
type: SKILL
implementationId: iron_will
name: "&7アイアンウィル"
description: "&7防御系能力を上げるパッシブスキル。"
icon: IRON_INGOT
cooldownTicks: 0
manaCost: 0
castTimeTicks: 0
requiredLevel: 1
passive:
  bindRequired: true
params:
  defenseFlat: 5
  magicDefenseFlat: 3
tags:
  - passive
  - defense
```

### 所持のみで常時有効なパッシブスキル

```yaml
schemaVersion: 1
id: mana_knowledge
type: SKILL
implementationId: mana_knowledge
name: "&bマナナレッジ"
description: "&7所持しているだけで最大 MP が上がる。"
icon: LAPIS_LAZULI
passive:
  bindRequired: false
params:
  maxManaFlat: 20
tags:
  - passive
  - support
```
