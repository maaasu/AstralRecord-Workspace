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
- API
  - `00_docs/20_API設計書/feature/11-skill/` を参照し、`/api/skill` から定義を取得する。

## 更新ルール（変更時に必ず更新する章）

- YAML スキーマ項目の増減:
  - [[13_1.00-モデル定義]]
  - `40_filebase/30.features.skill/skill.YAMLスキーマ定義.md`
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
