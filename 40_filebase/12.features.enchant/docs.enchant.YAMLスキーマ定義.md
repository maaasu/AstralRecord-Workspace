# Enchant 共通マスタ YAML スキーマ定義

装備個別の候補を廃止し、オーブから参照する重み付きエンチャント候補を定義します。

| キー | 型 | 必須 | 説明 |
|:--|:--|:--:|:--|
| `schemaVersion` | Integer | ○ | スキーマ版 |
| `id` | String | ○ | 共通エンチャントマスタID |
| `targets[].equipmentType` | String | ○ | `WEAPON` / `ARMOR` / `ACCESSORY` |
| `targets[].entries[].effectId` | String | ○ | 効果を一意に識別する安定ID。同じ装備へ重複付与不可 |
| `targets[].entries[].status` | String | ○ | 共有ステータスID |
| `targets[].entries[].type` | String | ○ | `FLAT` / `SCALAR` |
| `targets[].entries[].value` | String | ○ | 固定値または `min~max` |
| `targets[].entries[].weight` | Integer | ○ | 正の32-bit抽選重み。範囲は `1..2147483647` |

`FILL_ALL_EMPTY` は未付与 `effectId` の候補が空き枠数以上ある場合だけ原子的に成功します。
候補重みの合計はAPIが64-bit整数で計算し、32-bit合計を超える候補集合も抽選できます。
