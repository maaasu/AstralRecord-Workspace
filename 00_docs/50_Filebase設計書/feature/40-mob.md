# Mob 設計

## 役割

Mob は、戦闘対象、案内役、商業・機能提供者など、ワールド上で行動する存在の共通定義です。enemy、boss、npc の固有方針は各文書を参照します。

## 設計方針

- 戦闘・非戦闘の役割を明確にし、不要な能力を持たせません。
- stats、skill、AI、装備、loot の組み合わせで役割を表現します。
- HP、攻撃、防御、行動阻害を同時に高くして、役割のない長期戦にしません。
- 使用可能なステータスは `StatusType.kt` を正とします。

## progression

プレイヤーが標準的に遭遇し、対処する段階を記載します。loot、quest、spawner はこの値を基準に接続します。

## メモ

現在実装されているモブ関連アイテムのマスタデータ（モブの装備・ドロップで参照される item）は、すべてデバッグ用の仮設定です。正式なゲームバランス、報酬、入手設計として扱わないでください。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\mob.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\status\model\StatusType.kt`
