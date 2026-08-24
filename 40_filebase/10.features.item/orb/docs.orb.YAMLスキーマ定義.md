# Orb アイテム YAML スキーマ定義

オーブはクリック時に対象装備一覧を開き、装備個体へ1種類の加工効果を実行するアイテムです。`category` は `orb` 固定です。

## `orb.effect`

| キー | 型 | 必須 | 説明 |
|:--|:--|:--:|:--|
| `type` | String | ○ | `ENHANCE` / `REPAIR` / `TRANSCENDENCE` / `ENCHANT` / `RUNE_ATTACH` / `RUNE_DETACH` |
| `targetSlots[]` | List<String> | 条件 | 強化対象スロット。`ENHANCE` で指定し、武器・防具・アクセサリを絞り込む |
| `rank` | Integer | 条件 | `ENHANCE` では現在状態ランク、`TRANSCENDENCE` では次に到達するランクの条件 |
| `rankMode` | String | × | `EXACT` は `rank` と一致、`AT_MOST` は対象ランクが `rank` 以下。既定 `EXACT` |
| `repairAmount` | Integer | 条件 | `REPAIR` の固定回復量 |
| `repairFull` | Boolean | 条件 | `true` の場合は最大耐久まで全回復 |
| `enchantMasterId` | String | 条件 | `ENCHANT` で使用する共通エンチャントマスタの必須参照。`enchant:<id>` 形式（例: `enchant:enchant001`）で指定する |
| `enchantOperation` | String | 条件 | `OVERWRITE_RANDOM` / `FILL_ONE_EMPTY` / `FILL_ALL_EMPTY` |

強化は装備マスタの次レベルにある `successRate` と `failAction` を使用し、追加素材・通貨は消費しません。成功・失敗を問わず試行開始時にオーブ1個を消費します。修理済み、条件外ランク、空き枠不足など実行不能な装備は一覧へ表示しません。

`FILL_ALL_EMPTY` は全空き枠を1個で埋めます。同一 `effectId` は重複せず、全枠分の未付与候補がない場合は無変更・無消費です。

`RUNE_ATTACH` は対象装備を選択後、所持ルーンを1個選んで装着します。`RUNE_DETACH` は装着済みルーンを1個選んで取り外し、通常インベントリへ返却します。これら二種はオーブ自体を消費しません。
