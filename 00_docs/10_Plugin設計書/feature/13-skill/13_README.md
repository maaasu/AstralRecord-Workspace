# 13_README

このディレクトリは `feature/skill` と、skill 所持状態の生成元となる `feature/skilltree` の設計書です。採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skilltree/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（skill 固有 `5800` 系、shared event の `E_3002`、shared GUI の `E_5601`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_5800` から `P_5811`、`P_5848`、`P_5849`）

## ドキュメント一覧（推奨順）

1. [[13_0.00-概要]]
2. [[13_1.00-モデル定義]]
3. [[13_2.00-ユースケース]]
4. [[13_3.00-索引]]
5. [[13_3.01-イベント]]
6. [[13_3.02-サービス]]
7. [[13_3.03-コマンド]]
8. [[13_3.04-リポジトリ]]
9. [[13_3.06-レジストリ・キャッシュ]]
10. [[13_3.07-GUI・View]]
11. [[13_3.09-モデル操作]]
12. [[13_4.00-統合フロー]]
13. [[13_4.01-スキルバインドGUI]]
14. [[13_5.00-例外・ログ・運用]]
15. [[13_6.00-発動スキル追加ガイド]]
16. [[13_9.00-未決事項]]（必要時）

## 依存 feature

- `player` / `account` / `status`: player caster、session lifecycle、MP / EN、status modifier を扱う。
- `mob`: `MobSkillCaster`、AI の skill 選択、頭上 cast bar を扱う。
- `combat`: active skill の damage、condition、threat、knockback を共通経路へ委譲する。
- `inventory` / `menu` / `player-interaction`: bind GUI、hotbar shortcut、action ring 入力調停を扱う。
- `class`: skilltree 解放状態と class point / passive point を所持 skill 判定へ供給する。
- AstralRecordApi `/api/skill`、`/api/skill-bind-presets`: skill definition と account preset の永続正本。
- `40_filebase/50.features.skill/*` / `40_filebase/55.features.skill_tree/*`: API が配信する定義と tree 構造の正本。

## 更新ルール（変更時に必ず更新する章）

- skill definition / kind / resource / params 変更: [[13_1.00-モデル定義]]、[[13_3.04-リポジトリ]]、[[13_3.09-モデル操作]]、[[13_6.00-発動スキル追加ガイド]]
- cast 検証・詠唱・cooldown・lifecycle 変更: [[13_3.01-イベント]]、[[13_3.02-サービス]]、[[13_4.00-統合フロー]]
- executor 登録・built-in catalog 変更: [[13_3.06-レジストリ・キャッシュ]]、[[13_5.00-例外・ログ・運用]]、[[13_6.00-発動スキル追加ガイド]]
- bind preset / GUI / ownership 変更: [[13_1.00-モデル定義]]、[[13_3.01-イベント]]、[[13_3.02-サービス]]、[[13_3.04-リポジトリ]]、[[13_3.07-GUI・View]]、[[13_4.01-スキルバインドGUI]]
- action ring / skilltree 入力候補変更: [[13_3.01-イベント]]、[[13_4.00-統合フロー]]、[[28_3.01-イベント]]
- ログ・player message 変更: [[13_5.00-例外・ログ・運用]]
