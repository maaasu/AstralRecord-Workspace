# Bundle Sound 設計

## 役割

Bundle Sound は、bundle 開封時に再利用するサウンド演出を定義します。

## 設計方針

- 複数の bundle で共有する演出だけを独立マスタにします。
- sound、volume、pitch は開封結果の重要度と周囲への影響を考慮します。
- 報酬内容や入手場所など、bundle 本体の意味を持たせません。

## progression

演出定義自体は進行に属さないため `0` とします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\bundle_sound\_bundle_sound.YAMLスキーマ定義.md`
