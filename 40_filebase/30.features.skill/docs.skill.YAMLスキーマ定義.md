# Skill YAML スキーマ定義

`40_filebase/30.features.skill/*.yml` に配置するスキル定義の schema です。

スキル種別は plugin 側の `SkillExecutor.kind()` が正本です。filebase 側ではパッシブスキルの場合に `passive.bindRequired` でバインド必要可否を定義します。

発動スキルのリソース種別と消費量は、共通項目 `resourceType` / `resourceCost` へ定義します。`params` は実装固有の拡張値だけに使用し、リソース、クールダウン、詠唱時間などの共通項目を重複して定義しません。

## 共通定義

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `schemaVersion` | Integer | 必須 | - | 現在は `1` |
| `id` | String | 必須 | - | スキル ID。自動生成ID `00_skill_gem_<id>` が100文字以内となる長さにする |
| `type` | String | 必須 | - | `SKILL` |
| `implementationId` | String | 必須 | - | plugin 側実装 ID |
| `name` | String | 必須 | - | 表示名 |
| `description` | String | 任意 | `null` | 説明文 |
| `icon` | String | 任意 | `null` | Material 名 |
| `lore` | List<String> | 任意 | `[]` | 詳細表示 lore |
| `cooldownTicks` | Long | 任意 | `0` | クールダウン |
| `cooldownId` | String | 任意 | `id` | 同一プレイヤー内で共有するクールダウン ID。発動スキル自身のクールダウン時間を共有グループへ設定する |
| `resourceType` | String | 任意 | `MANA` | 消費リソース種別。`MANA` / `ENERGY` |
| `resourceCost` | Double | 任意 | `0` | `resourceType` で指定したリソースの消費量。0以上を指定する |
| `manaCost` | Double | 任意 | `0` | 旧定義との互換用 MP 消費量。新規定義では使用せず `resourceCost` を使う |
| `castTimeTicks` | Long | 任意 | `0` | 詠唱時間。冷気中は最終値が2倍 |
| `requiredLevel` | Integer | 任意 | `1` | 必要レベル |
| `onCast` | Map | 任意 | `null` | 発動時演出設定 |
| `onCast.sound` | String | 任意 | `null` | 再生する sound key |
| `passive` | Map | 任意 | `null` | パッシブ設定 |
| `passive.bindRequired` | Boolean | 任意 | `true` | `false` は所持のみで常時有効 |
| `maxLevel` | Integer | 任意 | `1` | 習得済み個体の最大レベル |
| `levels[]` | List | 任意 | `[]` | 対象レベルへ上がる際に前レベルから加算する差分 |
| `levels[].level` | Integer | 必須 | - | 差分を適用する到達レベル（2以上、`maxLevel` 以下） |
| `levels[].cooldownTicksDelta` | Long | 任意 | `0` | 基礎クールダウンへの加算 tick |
| `levels[].resourceCostDelta` | Double | 任意 | `0` | 基礎消費量への加算 |
| `levels[].castTimeTicksDelta` | Long | 任意 | `0` | 基礎詠唱時間への加算 tick |
| `levels[].paramDeltas` | Map<String, Double> | 任意 | `{}` | 数値 params への加算。非数値 params には使用不可 |
| `levels[].statusModifiers[]` | List | 任意 | `[]` | このスキルの計算中だけ加算するステータス補正。共有カタログの既知status IDだけを指定 |
| `sigilSlotsByLevel[]` | List | 任意 | `[]` | 指定レベル以降のシジル装着可能数。現在レベル以下で最大の定義を採用 |
| `allowedSigilIds` | List<String> | 任意 | `[]` | このスキルへ合成可能なシジル ID |
| `gem.icon` | String | 任意 | `icon` | 自動生成ジェムのアイコン |
| `gem.rarity` | String | 必須 | - | 自動生成ジェムのレアリティ。DTO既定値へ暗黙fallbackせず各マスタで明示 |
| `gem.tradeable` | Boolean | 任意 | `false` | 自動生成ジェムを取引可能にするか |
| `gem.sellable` | Boolean | 任意 | `false` | 自動生成ジェムを売却可能にするか |
| `params` | Map<String, Any> | 任意 | `{}` | 実装固有の拡張パラメータ。共通項目は定義しない |
| `tags` | List<String> | 任意 | `[]` | `76.shared.tag/v1.tags.yml`の`SKILL`対象タグID |

## 職業発動スキルの定義方針

- プレイヤー向け職業発動スキルは、`id` と `implementationId` を同じ値にします。
- ID は `swordsman_` / `hunter_` / `mage_` の職業 prefix と lowercase snake_case を組み合わせます。
- `gem` オブジェクトと空でない `gem.rarity` は必須です。`gem.icon` 未指定時はスキルの `icon`、さらに未指定なら通常アイコンを使用します。
- 当たり判定、攻撃種別、倍率、状態異常、演出の詳細は `implementationId` に対応する Plugin 実装を正本とし、YAML の `params` へ大量の調整値を複製しません。
- プレイヤーが効果を判断できる倍率、範囲、時間は `description` / `lore` に記載し、実装変更時は同時に同期します。
- リソース種別、消費量、クールダウン、詠唱時間、必要レベル、共通発動音は、それぞれの top-level 項目へ定義します。

## 共通攻撃 executor の params

既存の `normal_attack` など、1つの executor を複数マスタで共有する実装は次の戦闘パラメータを使用します。職業発動スキルにはこの一覧を機械的に複製しません。

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `attackType` | String | 任意 | `MELEE` | `MELEE` / `RANGED` / `MAGIC`。攻撃力・能力値・防御力の参照元を決める |
| `damageComponents` | List | 任意 | 無属性100% | 属性別ダメージ倍率。各成分を独立して属性補正後に合算する |
| `damageComponents[].element` | String | 必須 | - | `NONE` / `FIRE` / `ICE` / `LIGHTNING` |
| `damageComponents[].ratio` | Double | 必須 | - | 攻撃種別から求めた攻撃力に掛ける倍率。`0.8` は80% |
| `activationRange` | Double | 任意 | `hitRange` | Mob AI が発動を開始する最大距離 |
| `conditions` | List | 任意 | `[]` | 命中時に付与する状態異常定義 |
| `castSound` | String | 任意 | `null` | 詠唱開始 sound key |
| `castSoundVolume` | Double | 任意 | `1.0` | 詠唱開始音量 |
| `castSoundPitch` | Double | 任意 | `1.0` | 詠唱開始音ピッチ |

`damageComponents` を省略したスキルは `NONE` 1.0 として扱います。たとえば火0.6と、氷0.3＋雷0.3は属性補正前の総倍率がどちらも0.6なので、基礎DPSは同じです。

## conditions params

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `conditions[].type` | String | 必須 | - | `BURNING` / `FROZEN` / `CHILLED` / `SHOCKED` / `POISON` / `BLINDNESS` / `WEAKNESS` / `HEALING_INHIBITION` |
| `conditions[].chance` | Double | 任意 | `100` | 基礎付与確率（%） |
| `conditions[].durationTicks` | Long | 任意 | 種別既定 | 効果時間 |
| `conditions[].strength` | Double | 任意 | `1.0` | 同種重複時の強さ比較値 |
| `conditions[].basePower` | Double | 任意 | 種別既定 | 固定DoT値 |
| `conditions[].powerCoefficient` | Double | 任意 | 種別既定 | 付与元 `ATTACK` に対するDoT係数 |
| `conditions[].healthRate` | Double | 任意 | 種別既定 | 1 tick処理ごとのHP割合。`0.03` は3% |
| `conditions[].tickIntervalTicks` | Integer | 任意 | 種別既定 | DoT間隔。0以下はDoTなし |

同種状態異常は強い効果だけを保持し、終了時刻は既存より後になる場合だけ延長します。DoTは会心しません。状態異常の付与耐性とDoT耐性は独立し、DoT貫通はDoT耐性だけを相殺します。

## 状態異常既定値

| 種別 | 時間 | 既定効果 |
| --- | ---: | --- |
| `BURNING` | 100 tick | 20 tickごとに最大HP1% |
| `FROZEN` | 40 tick | 全行動不能 |
| `CHILLED` | 100 tick | 移動50%、詠唱時間2倍 |
| `SHOCKED` | 100 tick | 最大HP1%/秒、16～32 tickごとに6 tick移動・ジャンプ不能 |
| `POISON` | 120 tick | 現在HP3%/秒、HP1未満にしない |
| `BLINDNESS` | 100 tick | バニラ盲目 |
| `WEAKNESS` | 100 tick | 与える最終ダメージ50% |
| `HEALING_INHIBITION` | 100 tick | 通常の全回復を無効化 |

## 職業発動スキルの例

```yaml
schemaVersion: 1
id: swordsman_crescent_slash
type: SKILL
implementationId: swordsman_crescent_slash
name: "&f半月斬り"
description: "&7前方を半月状に薙ぎ払う近接技。"
icon: IRON_SWORD
cooldownTicks: 40
resourceType: ENERGY
resourceCost: 12
castTimeTicks: 0
requiredLevel: 1
maxLevel: 5
levels:
  - level: 2
    statusModifiers:
      - status: SKILL_DAMAGE_INCREASE
        value: 5
sigilSlotsByLevel:
  - { level: 1, slots: 1 }
  - { level: 3, slots: 2 }
allowedSigilIds:
  - cooldown_sigil
gem:
  rarity: COMMON
  tradeable: false
  sellable: false
onCast:
  sound: entity.player.attack.sweep
tags:
  - active
  - swordsman
  - melee
```

## 共通攻撃 executor の例

```yaml
params:
  attackType: MAGIC
  damageComponents:
    - element: FIRE
      ratio: 0.8
  hitRange: 8
  conditions:
    - type: BURNING
      chance: 35
      durationTicks: 100
```
