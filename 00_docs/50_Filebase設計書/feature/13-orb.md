# Orb 設計

## 役割

Orb は、通常プレイヤーインベントリからクリックして装備強化、装備修理、状態変化、エンチャントのいずれかを開始する消費 item です。

## 設計方針

- `category` は `orb` とし、1 itemの `orb.effect.type` には1種類の操作だけを指定します。
- 強化は `targetSlots`, `rank`, `rankMode` で対象を絞ります。武器、防具、アクセサリの用途が分かれる場合は別オーブにします。
- 修理は正の `repairAmount` または `repairFull: true` のどちらかを指定します。
- 状態変化の `rank` は現在rankではなく、実行する即時次状態の目標rankです。`AT_MOST` は目標rankが指定値以下なら使用できます。
- エンチャントは共通 `enchantMasterId` を `enchant:<id>` 形式の必須参照で指定し、`OVERWRITE_RANDOM` / `FILL_ONE_EMPTY` / `FILL_ALL_EMPTY` のいずれかを指定します。
- 成立した操作は成功・強化失敗を問わず必ず1個消費します。`FILL_ALL_EMPTY` も全空き枠に対して1個です。
- 強化・修理・エンチャントは追加素材・通貨を使いません。状態変化だけは装備マスタの必要素材・通貨を確認画面で消費します。
- lore には対象ランク・用途などオーブ自体の条件を記載し、対象装備のステータスを動的に列挙しません。

## progression

対象装備と同じ段階から、用途の希少性に応じて1段階後までを基準とします。上位rank用オーブは、そのrankへ進める装備より先に大量供給しません。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\orb\docs.orb.YAMLスキーマ定義.md`
- 共通 enchant: `E:\AstralRecord-Workspace\40_filebase\12.features.enchant\docs.enchant.YAMLスキーマ定義.md`
- equipment: `E:\AstralRecord-Workspace\40_filebase\10.features.item\equipment\docs.equipment.YAMLスキーマ定義.md`
