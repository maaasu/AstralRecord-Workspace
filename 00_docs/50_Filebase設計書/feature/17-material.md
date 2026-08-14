# Material 設計

## 役割

Material は、敵、採集、報酬などから入手し、recipe、強化、交換、売却へ接続する素材です。

## 設計方針

- 入手元と主要な使い道を少なくとも1つずつ成立させます。
- 通常素材、希少素材、専用素材は入手難度と用途で分けます。
- 素材単体へ戦闘性能を持たせません。
- 世界観上の由来や配置場所は YAML 本文や個別ワールド定義で扱い、共通設計へ固定しません。

## progression

- 通常素材は主な入手元と同値を基準にします。
- 希少素材は入手元と同値から `+1` を許容します。
- 複数段階で使う共通素材は、最初に標準入手できる段階を記載します。

## フック

`hook` は progression 10 の通常素材です。iconは `TRIPWIRE_HOOK`、最大スタックは64で、`hookshot` の有効な射出ごとに1個だけ消費します。本変更ではloot・shop・quest・recipeの入手経路は追加せず、既存の管理者付与から検証・配布します。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\material\docs.material.YAMLスキーマ定義.md`
