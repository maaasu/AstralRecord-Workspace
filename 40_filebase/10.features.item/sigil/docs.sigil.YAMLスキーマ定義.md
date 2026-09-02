# SIGIL (シジル) YAML スキーマ定義

習得済みスキル個体へ消費装着し、スキルの計算中だけステータス補正または個別ロジックを適用するシジルアイテムを表現します。
通常のアイテム共通項目（`schemaVersion` / `id` / `category` / `name` など）は
`40_filebase/10.features.item/docs.item.YAMLスキーマ定義.md` を参照し、本書では Sigil 固有項目と装着ルールを定義します。

`category` は `sigil` 固定です。シジルの装着可能な ID とスロット数はシジル側ではなく、対象スキルの
`allowedSigilIds` と `sigilSlotsByLevel` で定義します。

## スキーマ定義

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `maxStack` | Integer | × | `64` | シジルを通常インベントリで保持するときの最大スタック数。 |
| `sigil` | Map | ○ | - | シジル固有定義。`category: sigil` では必ず指定する。 |
| `sigil.equipGroupId` | String | ○ | - | 1～128文字。同じ習得済みスキル個体へ重複装着できないグループ ID。シジルの `id` とは独立して定義する。 |
| `sigil.modifiers[]` | List | × | `[]` | 対象スキルの計算中だけ加算するステータス補正のリスト。 |
| `sigil.modifiers[].status` | String | ○ | - | `40_filebase/75.shared.status/v1.status_types.yml` に存在する共有ステータス ID。 |
| `sigil.modifiers[].value` | Double | ○ | - | 対象ステータスへ加算する値。有限値で指定し、同じステータスは有効な装着シジル間で合算する。 |

## `sigil.equipGroupId`

`equipGroupId` は、同じ種類の効果を異なるシジル ID で重複装着させないためのグループ ID です。
同じ習得済みスキル個体へ、同じ `equipGroupId` を持つシジルを複数装着することはできません。
たとえば `cooldown_sigil` と `cooldown_sigil_ii` はどちらも `cooldown_reduction` を指定するため、同一個体へ同時には装着できません。

装着可否は次の条件をすべて満たす必要があります。

- 対象スキルの `allowedSigilIds` にシジル ID が含まれている。
- 対象スキルの現在レベルで `sigilSlotsByLevel` が定める空きスロットがある。
- 同じ `equipGroupId` のシジルが対象個体へ装着されていない。

`allowedSigilIds` と `sigilSlotsByLevel` の定義は、
`40_filebase/30.features.skill/docs.skill.YAMLスキーマ定義.md` を参照してください。

## `sigil.modifiers[]`

`modifiers` は、シジルを装着した習得済みスキルの計算中にだけ適用する補正です。
補正方式の `type` は持たず、`value` を対象ステータスへ加算します。パーセンテージとして表示・計算するかどうかは、
指定した status の共有カタログ定義に従います。

`status` には独自の文字列を指定せず、共有ステータスカタログに登録済みの ID だけを使用します。
`value` は `NaN` や無限大を含まない有限値にしてください。

例として、`COOLDOWN_REDUCTION` は共有カタログ上で単位が `%` のステータスであるため、
`value: 5` はクールダウン短縮の `+5` として扱われます。

## ロジック変更型シジル

ステータス補正ではなくスキル固有の動作を変更するシジルは、`sigil.modifiers` を空リストにします。
この場合も `equipGroupId` は必須です。

個別スキル executor が装着済みシジルのアイテム ID を `hasSigil` で判定する場合にだけ、
その ID に対応するロジック変更が適用されます。シジル YAML だけで新しいスキル動作や executor を定義することはできません。
現行の Plugin ソースでは `homing_fireball_sigil` を `hasSigil` で判定する処理は確認できないため、
この ID の YAML が存在するだけで追尾動作が有効になるわけではありません。

## 装着・脱着

シジルは単独使用するアイテムではなく、シジル用オーブから対象の習得済みスキル個体へ操作します。
`SIGIL_ATTACH` は対応するオーブとシジルを各1個消費して装着し、`SIGIL_DETACH` は対応するオーブを1個消費して
装着済みシジルを1個取り外し、シジルを通常インベントリへ返却します。操作の詳細は
`40_filebase/10.features.item/orb/docs.orb.YAMLスキーマ定義.md` を参照してください。

## YAML 例

### 例1: ステータス補正型シジル

以下は現行の `cooldown_sigil` マスタです。`COOLDOWN_REDUCTION` を `+5` 加算し、
`cooldown_reduction` グループとして同グループの他シジルと排他的に扱います。

```yaml
schemaVersion: 1
id: cooldown_sigil
category: sigil
name: "&b短縮のシジル"
icon: AMETHYST_SHARD
rarity: UNCOMMON
maxStack: 64
saleValue: 20
lore:
  - "&7合成したスキルだけのクールダウン短縮を5%増加する。"
unTradeable: false
unSellable: false
sigil:
  equipGroupId: cooldown_reduction
  modifiers:
    - status: COOLDOWN_REDUCTION
      value: 5
```

### 例2: ロジック変更型シジル

以下は現行の `homing_fireball_sigil` マスタです。ステータス補正を持たないため、
`modifiers` は空リストにします。追尾動作を提供する場合は、対応するスキル executor 側で ID を判定する実装が別途必要です。

```yaml
schemaVersion: 1
id: homing_fireball_sigil
category: sigil
name: "&d追尾火焔のシジル"
icon: FIREWORK_STAR
rarity: EPIC
maxStack: 64
saleValue: 100
lore:
  - "&7火焔弾が近くの対象を追尾するようになる。"
unTradeable: false
unSellable: false
sigil:
  equipGroupId: fireball_trajectory
  modifiers: []
```

## 正本参照

- 共通 item 項目: `40_filebase/10.features.item/docs.item.YAMLスキーマ定義.md`
- 共有ステータス ID と表示単位: `40_filebase/75.shared.status/v1.status_types.yml`
- 装着許可 ID とレベル別スロット: `40_filebase/30.features.skill/docs.skill.YAMLスキーマ定義.md`
- シジル装着・脱着オーブ: `40_filebase/10.features.item/orb/docs.orb.YAMLスキーマ定義.md`
