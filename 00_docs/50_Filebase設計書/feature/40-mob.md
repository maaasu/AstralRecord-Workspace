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
- `drops.items[].luckAffected` は、特別な設計理由がない限り `true` とし、幸運補正を反映します。`false` にする場合は、個別定義または関連設計書へ理由を残します。

## progression

プレイヤーが標準的に遭遇し、対処する段階を記載します。loot、quest、spawner はこの値を基準に接続します。

## 高レベルダンジョンのシールドブレイク

- `recommendedLevel` が7以上の Dungeon の `encounter.normalMobPool` または `encounter.bossMobId` から参照される `ENEMY` / `BOSS` は、実際に使用する Mob レベルの標準ソードマンの最大 `MAX_SHIELD` を基準に、`baseStats`（レベル別なら対象 `levels[]`）へ `SHIELD_BREAK` を定義します。
- ここでいう標準ソードマンは、対象 Mob レベルの `swordsman` class 基礎値に、対象レベル帯の盾を1つだけ無強化で装備し、skilltree の `MAX_SHIELD` node を取得していない再現用構成とします。現行 Lv.8 は `swordsman` の `MAX_SHIELD +5`（`growthPerLevel` に同 status なし）と未強化 `fang_shield` の `MAX_SHIELD +15`を合算した最大Shield 20を基準にします。
- `SHIELD_BREAK` は絶対的なシールド量ではなく、Plugin の戦闘計算で算出した基礎シールドダメージへ加算されます。標準 enemy は対象レベルのソードマンの標準構成に対して直接攻撃1回で最大Shieldのおおむね半分を削れる値を初期目安とし、boss は主な直接攻撃の命中回数・多段 skill・ギミックを含めて同等以上のシールド圧力になるよう調整します。
- Dungeon の `recommendedLevel` と Mob の `level` / `encounter.*Level` は別値です。基準に使う Mob レベルは実際に spawn されるレベルとし、単に Dungeon の推奨レベルや Mob レベルの数値を `SHIELD_BREAK` へ転記しません。
- 現行の `iluvatar_sanctum` Lv.8 では、上記の最大Shield 20に対して通常敵3体へ `SHIELD_BREAK: 5`、bossへ `SHIELD_BREAK: 9` を設定します。将来、基準装備・強化・skilltree構成または攻撃ローテーションを変更する場合は、同じ基準構成を再計算して値を更新します。
- `SHIELD_BREAK` の値を決めるときは、上記基準構成のソードマンの最大 `MAX_SHIELD`、Mob の解決攻撃力、攻撃間隔、多段 skill を合わせて試算します。Mob 自身の `shield.max` を設定する場合も、シールド防御とシールドブレイクを別の値として扱います。

## メモ

現在実装されているモブ関連アイテムのマスタデータ（モブの装備・ドロップで参照される item）は、すべてデバッグ用の仮設定です。正式なゲームバランス、報酬、入手設計として扱わないでください。

## 正本参照

- 戦闘・ゲームバランス: ステータス、skill、AI、装備、lootなど戦闘性能に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\docs.mob.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
