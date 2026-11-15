# アカウント進行プリセット YAML スキーマ定義

職業とビルドごとの戦闘構成を定義します。配置先はこのディレクトリ直下の `v1.<id>.yml` です。型と必須項目は [JSON Schema](schemas/progression-preset.v1.schema.json) を参照してください。

| キー | 意味 |
| --- | --- |
| `schemaVersion` | `1` |
| `type` | `PROGRESSION_PRESET` |
| `id` | ファイル名に含める一意のID |
| `name` | プリセットの表示名 |
| `classId` | 対象職業のID。管理者職は対象外 |
| `buildId` | 対象職業内で一意のビルドID |
| `levels` | レベル別の戦闘構成。最初の行をレベル1にして昇順で記載 |
| `levels[].level` | この構成を使い始めるプレイヤーレベル（1〜50） |
| `levels[].classLevel` | 適用する職業レベル |
| `levels[].nodes` | 習得済みノードの整数ID一覧。接続と前提条件を満たす構成を指定 |
| `levels[].equipment` | `slot` と `item:<id>` を値に持つ装備の一覧。同じ枠は一度だけ指定。メインハンドは必須。省略した枠は適用時に外す |
| `levels[].skills` | `slot`（1〜8）と `skill:<id>` を値に持つ `skill` の一覧。発動可能なアクティブスキルを1番枠に指定 |
| `levels[].passives` | `slot`（1〜12）と `skill:<id>` を値に持つ `skill` の一覧。省略時は空欄 |
| `equipment[].enhanceLevel` | 適用する装備の強化値。省略時は0。正の値は状態変化前の装備マスターに定義された強化段階を指定 |
| `skills[].skillLevel` / `passives[].skillLevel` | 適用するスキル個体のレベル。省略時は1。スキルマスターの最大レベルまで指定可能 |
| `skills[].sigils` / `passives[].sigils` | 装着するシジルの一覧。各項目に1始まりの `slot` と `item:<id>` を値に持つ `item` を指定。省略時は装着なし |

指定レベル以下で最も近い行を使います。たとえばレベル1、5、10の構成を登録すると、レベル8を指定した場合のプレイヤーレベルは8、戦闘構成はレベル5の行になります。各行は独立したスナップショットです。

`equipment[].slot` は通常装備の `MAIN_HAND`、`OFF_HAND`、`HEAD`、`CHEST`、`LEGS`、`FEET`、スキルブックの `SKILLBOOK`、またはアクセサリの `AMULET`、`TALISMAN_1`、`TALISMAN_2`、`CHARM_1`、`CHARM_2`、`CHARM_3`、`CORE`、`RELIC_1`、`RELIC_2` を指定します。最大16枠で、同じ枠は重複できません。`OFF_HAND` はアクセサリロードアウトの0番枠です。アクセサリは指定枠と装備マスターの `tag` が一致する必要があります。各アイテムは1枠に1個体として付与します。同じマスターと強化値の装備中個体は同じ枠を優先し、別枠の要求にも一度だけ再利用します。使わなかった個体だけをバッグへ戻します。

スキルブックの `usableSkills` は、そのブックの装備条件を満たす場合、同じチェックポイントのアクティブ・パッシブ設定で使用できます。通常装備と同様に、チェックポイントのプレイヤーレベル・職業・職業レベルが装備マスターの条件を満たす必要があります。スキル設定では、職業・ノード・スキルブックから利用できるスキルを指定します。

シジルは、スキルマスターの `allowedSigilIds` に含まれるシジルアイテムを指定します。枠数は適用する `skillLevel` と `sigilSlotsByLevel` で決まり、同じ枠や `equipGroupId` のシジルは同一スキル内で重複できません。
