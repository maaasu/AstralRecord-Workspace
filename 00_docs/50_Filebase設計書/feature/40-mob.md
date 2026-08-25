# Mob 設計

## 役割

Mob は、戦闘対象、案内役、商業・機能提供者など、ワールド上で行動する存在の共通定義です。enemy、boss、npc の固有方針は各文書を参照します。

## 設計方針

- 戦闘・非戦闘の役割を明確にし、不要な能力を持たせません。
- stats、skill、AI、装備、loot の組み合わせで役割を表現します。
- 通常攻撃は `ai.combat.normalAttack`、固有行動は `ai.combat.skills` として別に定義します。`normalAttack` を省略した Mob は通常攻撃をしません。
- Mob スキル ID は `mob_` 接頭辞とし、Mob マスターから参照します。player 用 skill master、skill gem、習得・装備スロットとは混在させません。
- Mob スキルの詳細ロジックは ID ごとの Java executor に置き、マスターには発動距離・クールダウン・詠唱時間と少数の数値パラメーターだけを置きます。固有パラメーターの必須性と範囲は executor の JavaDoc を正とします。
- HP、攻撃、防御、行動阻害を同時に高くして、役割のない長期戦にしません。
- 使用可能なステータスは `StatusType.kt` を正とします。

## progression

プレイヤーが標準的に遭遇し、対処する段階を記載します。loot、quest、spawner はこの値を基準に接続します。

## メモ

現在実装されているモブ関連アイテムのマスタデータ（モブの装備・ドロップで参照される item）は、すべてデバッグ用の仮設定です。正式なゲームバランス、報酬、入手設計として扱わないでください。

## 正本参照

- 戦闘・ゲームバランス: ステータス、skill、AI、装備、lootなど戦闘性能に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\docs.mob.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
