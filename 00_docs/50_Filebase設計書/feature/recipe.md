# Recipe 設計

## 役割

Recipe は、material などの入力を equipment、consumable、別素材へ変換する制作・加工定義です。

## 設計方針

- 入力、数量、出力、利用条件を明確にします。
- 素材の主要な消費先を作り、余剰素材の循環を支えます。
- 1つの recipe だけで複数段階の進行を飛ばさせません。
- 出力を直接入手する経路がある場合、制作経路の利点を時間、確実性、品質などで持たせます。

## progression

標準的に制作可能になる段階を記載します。出力は主素材と同値から `+1` を基準とし、必要設備や解放条件が遅い場合は実際の最速制作段階を採用します。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\85.shared.recipe\recipe.YAMLスキーマ定義.md`
