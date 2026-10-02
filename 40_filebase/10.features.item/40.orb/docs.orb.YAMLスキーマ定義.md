# Orb アイテム YAML スキーマ定義

オーブはクリック時に対象装備または習得済みスキルの一覧を開き、1種類の加工効果を実行するアイテムです。`category` は `orb` 固定です。

## `orb.effect`

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `type` | String | ○ | - | `ENHANCE` / `REPAIR` / `TRANSCENDENCE` / `ENCHANT` / `RUNE_ATTACH` / `RUNE_DETACH` / `SIGIL_ATTACH` / `SIGIL_DETACH` / `PET_REVIVE` |
| `targetSlots[]` | List<String> | 条件 | emptyList | 強化対象スロット。`ENHANCE` で指定し、武器・防具・アクセサリを絞り込む |
| `rank` | Integer | 条件 | Null | `ENHANCE` では現在状態ランク、`TRANSCENDENCE` では`rankBasis`で指定したランクの条件 |
| `rankBasis` | String | × | TARGET | `TRANSCENDENCE`の比較基準。`TARGET`は次に到達するランク、`CURRENT`は使用前の現在ランク。強化・修理は常に現在ランク |
| `rankMode` | String | × | EXACT | `EXACT` は `rank` と一致、`AT_MOST` は対象ランクが `rank` 以下 |
| `chargeSaleValue` | Boolean | × | false | trueの場合、成立した操作1回につきitemの非負の`saleValue`と同額のゴールドを消費する。`ENHANCE` / `REPAIR` / `TRANSCENDENCE`だけで指定可能。他効果の実行時は無視する |
| `repairAmount` | Integer | 条件 | Null | `REPAIR` の固定回復量 |
| `repairFull` | Boolean | 条件 | false | `true` の場合は最大耐久まで全回復 |
| `enchantMasterId` | String | 条件 | Null | `ENCHANT` で使用する共通エンチャントマスタの必須参照。`enchant:<id>` 形式（例: `enchant:enchant001`）で指定する |
| `enchantOperation` | String | 条件 | Null | `OVERWRITE_RANDOM` / `FILL_ONE_EMPTY` / `FILL_ALL_EMPTY` |

強化は装備マスタの次レベルにある `successRate` と `failAction` を使用します。`chargeSaleValue: true` の強化・修理・状態変化は売り値と同額のゴールドを消費し、状態変化に必要な素材・通貨へ加算します。強化は成功・失敗を問わず成立した試行でオーブ1個とゴールドを一体消費します。残高不足または実行条件不成立なら装備・オーブ・ゴールドを変更しません。複数回使用は各回で再確認し、不足した時点で終了します。修理済み、条件外ランク、空き枠不足など実行不能な装備は一覧へ表示しません。

`FILL_ALL_EMPTY` は全空き枠を1個で埋めます。同一 `effectId` は重複せず、全枠分の未付与候補がない場合は無変更・無消費です。

`RUNE_ATTACH` は対象装備を選択後、所持ルーンを1個選んで装着します。`RUNE_DETACH` は装着済みルーンを1個選んで取り外し、通常インベントリへ返却します。これら二種はオーブ自体を消費しません。

`SIGIL_ATTACH` は操作可能な習得済みスキルを選択後、対応するオーブと所持シジルを各1個、同一 transaction で消費して装着します。`SIGIL_DETACH` は対応するオーブを1個消費し、装着済みシジルを1個選んで取り外し、通常インベントリへ返却します。空き枠なし、非許可シジル、対象不在などの業務失敗では、オーブとシジルを消費しません。

`PET_REVIVE` は死亡した所有ペットの一覧を開き、復活に成功したときだけオーブを1個消費します。装備のrankやtargetSlotsは指定しません。ペットマスターの `rules.reviveOrbItemId` と一致するitem IDを使用し、施設の復活素材は併用しません。
