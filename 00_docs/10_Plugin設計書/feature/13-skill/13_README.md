# 13_README

このディレクトリは `feature/skill` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/skill/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/skill/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/skill/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/skill/registry/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/skilltree/*`

## ドキュメント一覧（推奨順）

1. [[13_0.00-概要]]
2. [[13_1.00-モデル定義]]
3. [[13_2.00-ユースケース]]
4. [[13_3.00-索引]]
5. [[13_4.00-統合フロー]]
6. [[13_5.00-例外・ログ・運用]]
7. [[13_9.00-未決事項]]

## 依存 feature

- `class`
  - `starterSkills` / `levelSkills[].skill` の解決先として skill feature を利用する。
- `mob`
  - `ai.combat.skills` の実行対象として skill feature を利用する。
- `player`
  - 発動者・対象者の `AstPlayer` と状態をスキル実行コンテキストへ渡す。
- `status`
  - `manaCost` / `requiredLevel` / `params` 内のステータス依存ロジックの評価に利用する。
- `player-interaction`
  - skilltree操作、表示中action ring、weapon入力、新規action ring表示、`HOTBAR_SLOT`のinput lock / 表示中ring guardの排他制御は[[28_README]]を正本とする。
- API
  - `00_docs/20_API設計書/feature/11-skill/` を参照し、`/api/skill` から定義を取得する。

## 更新ルール（変更時に必ず更新する章）

- YAML スキーマ項目の増減:
  - [[13_1.00-モデル定義]]
  - `40_filebase/30.features.skill/docs.skill.YAMLスキーマ定義.md`
- `implementationId` 解決方式・レジストリ責務の変更:
  - [[13_0.00-概要]]
  - [[13_3.02-サービス]]
  - [[13_3.06-レジストリ・キャッシュ]]
- 実行コンテキスト・ `params` 読み取り契約の変更:
  - [[13_1.00-モデル定義]]
  - [[13_3.09-モデル操作]]
- API 取得経路やキャッシュ更新方式の変更:
  - [[13_3.04-リポジトリ]]
  - [[13_4.00-統合フロー]]
- スキルツリー進行状態のロード・保存・TPS 負荷対策の変更:
  - [[13_5.00-例外・ログ・運用]]
  - `00_docs/20_API設計書/feature/20-skilltree/`
- スキルツリー JSON の項目、配置・接続契約の変更:
  - `40_filebase/35.features.skilltree/docs.skilltree.JSONスキーマ定義.md`
  - `40_filebase/35.features.skilltree/schemas/`
- skilltreeクリック、action ring開閉・発動・表示中hotbar guard、新規表示条件の変更:
  - [[13_3.02-サービス]]
  - [[13_4.00-統合フロー]]
  - [[28_3.02-サービス]]
  - [[28_4.00-統合フロー]]

## SkillTree 実装方針

- SkillTree のプレイヤー操作はホットバー切替に依存しない方式へ変更する。
- 対象ノードの解決は毎 tick 監視ではなく、プレイヤー操作イベント起点で行う。
- ノードの表示テキスト・表示アイテムは skilltree マスタデータ読込時にキャッシュする。
- ゲーム内の構造編集機能は持たず、ノード定義と配置・接続は `60_tool/skilltree-editor/` のローカル Web エディターで編集する。
- SkillTree の表示距離は `48`、エッジ表示は `ALL` に固定し、プレイヤー別の表示オプションは持たない。
- ノードの world ラベルは距離別の段階表示にする。近距離は詳細表示、中距離は簡略表示、遠距離は非表示とする。
- ノード解放・解除後の更新はプレイヤー単位の派生キャッシュと遅延保存で扱う。
- 見た目の更新は dirty になった閲覧者と変更座標だけに限定する。
- `/masterdata reload` は filebase の node/structure JSON を再読込し、検証済みスナップショットを一括反映する。
- 2026-06-09: `skilltree` は独立実装を持つが、docs では skill feature の拡張範囲として扱う。スキル定義・スキル実行・スキルツリー進行状態の境界は本 README と `00_docs/20_API設計書/feature/20-skilltree/` を正本とする。
- 2026-06-09: class / playerclass は `starterSkills` / `levelSkills[].skill` の参照元であり、職業マスタや `/class` GUI の正本は本 feature ではない。
- `skill_tree` ワールド滞在中は、対象プレイヤーから見える他プレイヤーを AstralRecord 側で非表示にする。`skill_tree` 以外へ移動した時点で可視状態を通常へ戻す。
