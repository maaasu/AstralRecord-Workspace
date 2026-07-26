# Skill YAML スキーマ定義

`40_filebase/30.features.skill/*.yml` に配置するスキル定義の schema です。

スキル種別は plugin 側の `SkillExecutor.kind()` が正本です。filebase 側ではパッシブスキルの場合に `passive.bindRequired` でバインド必要可否を定義します。

## 共通定義

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
| `castTimeTicks` | Long | 任意 | `0` | 詠唱時間。冷気中は最終値が2倍 |
| `requiredLevel` | Integer | 任意 | `1` | 必要レベル |
| `onCast` | Map | 任意 | `null` | 発動時演出設定 |
| `onCast.sound` | String | 任意 | `null` | 再生する sound key |
| `passive` | Map | 任意 | `null` | パッシブ設定 |
| `passive.bindRequired` | Boolean | 任意 | `true` | `false` は所持のみで常時有効 |
| `params` | Map<String, Any> | 任意 | `{}` | 実装側パラメータ |
| `tags` | List<String> | 任意 | `[]` | 任意タグ |

## 攻撃スキル params

`normal_attack` などの攻撃スキル実装は次の戦闘パラメータを使用します。

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `attackType` | String | 任意 | `MELEE` | `MELEE` / `RANGED` / `MAGIC`。攻撃力・能力値・防御力の参照元を決める |
| `damageComponents` | List | 任意 | 無属性100% | 属性別ダメージ倍率。各成分を独立して属性補正後に合算する |
| `damageComponents[].element` | String | 必須 | - | `NONE` / `FIRE` / `ICE` / `LIGHTNING` / `POISON` / `LIGHT` / `DARK` |
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

## 例

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
