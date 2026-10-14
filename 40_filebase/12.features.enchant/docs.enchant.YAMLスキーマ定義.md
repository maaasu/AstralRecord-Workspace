# Enchant 共通マスタ YAML スキーマ定義

装備個別の候補を廃止し、付与・上書き・数値再抽選のオーブから参照する重み付きエンチャント候補を定義します。削除・固定操作はこのマスタを必要としません。

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

`enchant002` / `enchant003` は `enchant001` と同じ候補ID・効果種別・最低値・抽選重みを持ちます。FLATの上限はそれぞれ001の1.5倍 / 2倍、SCALARの上限は `1 + (001の上限 - 1) × 1.5 / 2` とします。数値再抽選でも使用オーブの参照マスタを使い、上位から下位のオーブを使うと上限も下位の値幅に戻ります。
