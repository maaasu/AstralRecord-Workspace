# Bundle Particle 設計

## 役割

Bundle Particle は、bundle 開封時に再利用するパーティクル演出を定義します。

## 設計方針

- 複数の bundle で共有する演出だけを独立マスタにします。
- 種類、個数、拡散範囲は視認性と処理負荷を考慮します。
- 報酬内容や入手場所など、bundle 本体の意味を持たせません。

## progression

演出定義自体は進行に属さないため `0` とします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\bundle_particle\_bundle_particle.YAMLスキーマ定義.md`
