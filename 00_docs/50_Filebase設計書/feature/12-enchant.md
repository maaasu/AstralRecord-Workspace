# Enchant 設計

## 役割

Enchant は、エンチャントオーブから装備へ付与する効果の共通抽選マスタです。装備 item 個別には抽選候補を持たせず、オーブが `enchantMasterId: enchant:<id>` 形式で本マスタを必須参照します。

## 設計方針

- `WEAPON` / `ARMOR` / `ACCESSORY` ごとに候補を分けます。
- 各候補はマスタ内で安定した `effectId`、status、type、value、`1..2147483647` の正の32-bit weight を持ちます。APIは候補重みを64-bit合計して抽選します。
- 同一装備に同じ `effectId` は重複しません。`FILL_ALL_EMPTY` で全空き枠を埋めるため、対象装備の標準 `maxSlots` 以上の異なる候補を用意します。
- weight は候補間の相対確率です。希少効果は小さくしてよいですが、0以下は使用しません。
- 装備種別に適用できる候補がない場合、Pluginは装備・オーブを変更せず「付与できるエンチャントがありません」と通知します。

## 現行の共通候補

`enchant001` は武器25候補、防具26候補、アクセサリー47候補を持ち、すべて `min~max` の値幅から付与時に値を確定します。効果の選択と値の抽選は別の処理です。値幅と重みの正本は候補YAMLです。

- 武器は汎用・近接・間接・魔法攻撃力の加算/乗数、筋力・器用さ・知力、命中・会心・会心ダメージ、発生元別/最終ダメージ、4属性ダメージ増加を候補とします。
- 防具は5基本能力値、汎用・近接・魔法防御力の加算/乗数、火・氷・雷・無属性の耐性、無属性ダメージ増加、無属性耐性上限補正、最大HP/MP/ENG/Shield、HP/MP/ENG回復力、シールドリチャージ短縮を候補とします。
- アクセサリーは武器/防具の候補をstatus/type単位で統合し、FLATの両端を半減、SCALARの1を超える増加分を半減します。共通の基本能力値を二重候補にせず、幸運の加算候補を追加します。
- 無属性のダメージ増加・耐性・耐性上限補正は、火・氷・雷の候補より控えめにします。無属性耐性のプレイヤー標準上限は50%で、耐性値と上限補正は別のステータスです。

SCALARは同じ装備の同じstatusのFLAT合計だけへ適用します。基礎値に限らず、強化・エンチャント・ルーンの加算値も対象です。加算元がない場合は効果を発揮しません。最終ダメージ倍率はステータス自身が割合値を表すため、候補はFLATによるポイント加算とします。

数値をIDへ埋め込んだ旧候補は新しい候補へ置き換えています。付与済み個体の保存済みstatus/type/valueを一括変換せず、次回付与/上書きから新候補を使用します。同じeffectId以外の同系効果を禁止する仕組みはありません。

## progression

共通マスタ自身は入手物ではないため、参照するオーブと対象装備の最も早い標準入手段階を基準にコメントを付けます。

## 正本参照

- 戦闘・ゲームバランス: ステータス、効果量、装備更新に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\12.features.enchant\docs.enchant.YAMLスキーマ定義.md`
- 装備: `E:\AstralRecord-Workspace\40_filebase\10.features.item\20.equipment\docs.equipment.YAMLスキーマ定義.md`
- オーブ: `E:\AstralRecord-Workspace\40_filebase\10.features.item\40.orb\docs.orb.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
