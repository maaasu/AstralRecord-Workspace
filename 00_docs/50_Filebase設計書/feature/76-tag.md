# Tag 設計

## 役割

Tag は、装備・クラス・スキル・スキルツリーノード・Mob・レシピ・採集条件を横断して使用する分類 ID と日本語表示情報を定義します。個別マスターへ保存する値はタグ ID とし、表示名や説明は共有カタログから参照します。

## 設計方針

- `40_filebase/76.shared.tag/v1.tags.yml` をタグ定義の正本とします。
- 既存タグは大文字・小文字を含む現在の ID を維持します。`BOW` と `bow` のような値は用途が異なる既存 ID として別々に扱います。
- `appliesTo[]` で設定可能なマスター対象を明示し、対象外のマスターでは使用しません。
- 新規タグは共有カタログへ日本語表示名・説明・用途を追加した後に個別マスターで使用します。
- Plugin の処理分岐で使用するタグ ID は生成定数を利用します。
- コード生成時に `40_filebase` 全体の `tag`、`tags`、`requiredToolTags`、`targetTags` を走査し、未登録 ID と用途不一致をエラーにします。
- スキルツリーエディターでは `SKILLTREE_NODE` 用タグを日本語で表示・選択し、ノード JSON には従来どおりタグ ID を保存します。

## 装備タグ

`equipment.tag` は装備種別を表します。アクセサリの `AMULET`、`TALISMAN`、`CHARM`、`CORE`、`RELIC` は Plugin の装備スロット判定に使用されます。武器・道具タグは採集マスターの `requiredToolTags[]` と同じ ID で照合します。

Rune の `targetTags[]` は、この `equipment.tag` に対する追加の適合条件です。`targetSlots[]` のスロット条件と組み合わせる場合は AND、同じ配列内は OR で判定します。

## 分類タグ

クラス、スキル、スキルツリー、Mob、レシピの `tags[]` は検索・分類・表示補助に使用します。現在 Plugin の直接分岐に使われていない値も、将来の参照時に意味が変わらないよう共有カタログで管理します。

## 正本参照

- タグカタログ: `E:\AstralRecord-Workspace\40_filebase\76.shared.tag\v1.tags.yml`
- YAML Schema: `E:\AstralRecord-Workspace\40_filebase\76.shared.tag\schemas\tag-catalog.v1.schema.json`
- カタログ項目仕様: `E:\AstralRecord-Workspace\40_filebase\76.shared.tag\docs.tags.YAMLスキーマ定義.md`
- 装備スロット分岐: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\inventory\model\AccessorySlotType.java`
- 採集道具照合: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\gathering\service\GatheringService.java`
