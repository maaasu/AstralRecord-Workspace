# Gathering 設計

## 役割

Gathering は、採取・採掘など、ワールド上の採集対象と報酬を定義します。

## 設計方針

- 採集方法、必要ツール、所要時間、報酬を1つの循環として設計します。
- 戦闘素材と採集素材の用途を完全に重複させず、入手経路の意味を残します。
- 必要ツールが採集対象より後の progression でしか手に入らない状態を避けます。
- 配置場所や景観は個別 world 側で扱います。
- `drops.items[].luckAffected` は、特別な設計理由がない限り `true` とし、幸運補正を反映します。`false` にする場合は、個別定義または関連設計書へ理由を残します。

## progression

必要ツールを含め、標準的に採集可能になる段階を記載します。通常報酬は採集対象と同値、希少報酬は同値から `+1` を基準にします。

## 正本参照

- 共通 YAML: `E:\AstralRecord-Workspace\40_filebase\42.features.gathering\docs.gathering.YAMLスキーマ定義.md`
- harvesting YAML: `E:\AstralRecord-Workspace\40_filebase\42.features.gathering\harvesting\docs.harvesting.YAMLスキーマ定義.md`
- mining YAML: `E:\AstralRecord-Workspace\40_filebase\42.features.gathering\mining\docs.mining.YAMLスキーマ定義.md`
