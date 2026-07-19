# Guide 設計

## 役割

Guide は、ゲーム内 GUI で機能、操作、進行上の判断材料を説明する読み物です。

## 設計方針

- 1つの guide は1つの操作または判断目的に絞ります。
- マスタ名は参照記法を使い、表示名を本文へ固定しません。
- Plugin の画面配置やページングを YAML 側へ重複定義しません。
- 実装済みの機能と参照先だけを案内します。

## progression

説明対象を初めて利用する直前の段階を基準にします。進行に依存しない常設ヘルプは `0` とします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\09.features.guide\docs.guide.YAMLスキーマ定義.md`
