# Enchant 設計

## 役割

Enchant は、エンチャントオーブから装備へ付与する効果の共通抽選マスタです。装備 item 個別には抽選候補を持たせず、オーブが `enchantMasterId: enchant:<id>` 形式で本マスタを必須参照します。

## 設計方針

- `WEAPON` / `ARMOR` / `ACCESSORY` ごとに候補を分けます。
- 各候補はマスタ内で安定した `effectId`、status、type、value、`1..2147483647` の正の32-bit weight を持ちます。APIは候補重みを64-bit合計して抽選します。
- 同一装備に同じ `effectId` は重複しません。`FILL_ALL_EMPTY` で全空き枠を埋めるため、対象装備の標準 `maxSlots` 以上の異なる候補を用意します。
- weight は候補間の相対確率です。希少効果は小さくしてよいですが、0以下は使用しません。
- 装備種別に適用できる候補がない場合、Pluginは装備・オーブを変更せず「付与できるエンチャントがありません」と通知します。

## progression

共通マスタ自身は入手物ではないため、参照するオーブと対象装備の最も早い標準入手段階を基準にコメントを付けます。

## 正本参照

- 戦闘・ゲームバランス: ステータス、効果量、装備更新に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\12.features.enchant\docs.enchant.YAMLスキーマ定義.md`
- 装備: `E:\AstralRecord-Workspace\40_filebase\10.features.item\equipment\docs.equipment.YAMLスキーマ定義.md`
- オーブ: `E:\AstralRecord-Workspace\40_filebase\10.features.item\orb\docs.orb.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
