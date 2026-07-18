# Bundle 設計

## 役割

Bundle は、複数の item または loot table を1回の開封単位としてまとめる item です。

## 設計方針

- 開封目的と主な報酬群を1つに絞ります。
- 直接定義と loot table 参照は、保守する正本が明確になる方法を選びます。
- 進行必須品を低確率の bundle だけに依存させません。
- 開封演出は報酬価値を誤認させない範囲にします。

## progression

最も早い標準入手段階を基準にし、内容物は同値から `+1` を基本とします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\bundle\_bundle.YAMLスキーマ定義.md`
