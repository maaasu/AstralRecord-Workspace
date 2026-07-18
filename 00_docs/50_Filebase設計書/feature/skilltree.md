# Skilltree 設計

## 役割

Skilltree は、skill や能力解放の順序、前提関係、選択分岐を定義します。

## 設計方針

- 開始ノード、前提ノード、排他または分岐の意味を明確にします。
- 一本道だけでなく、同程度の進行度に役割の異なる選択肢を用意します。
- 後続ノードが前提ノードの役割を単純に無効化しないようにします。
- 存在しない skill や未実装の解放条件を参照しません。

## progression

ノードを標準的に解放できる段階を記載します。前提ノードより小さい progression を持たせません。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\35.features.skilltree\skilltree.YAMLスキーマ定義.md`
- Plugin 設計: `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\13-skill`
- API 設計: `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\20-skilltree`
