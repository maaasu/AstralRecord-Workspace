# 10_README

このディレクトリは `feature/hud` の設計書です。採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/hud/service/PlayerHudService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/hud/view/PlayerHudView.java`

## ドキュメント一覧（推奨順）

1. [[10_0.00-概要]]
2. [[10_2.00-ユースケース]]
3. [[10_3.00-索引]]
4. [[10_3.02-サービス]]
5. [[10_3.07-View]]
6. [[10_4.00-統合フロー]]
7. [[10_5.00-例外・ログ・運用]]

## 依存 feature

- `player` / `account`: online session、アカウントモード、レベル、経験値を参照する。
- `status`: HP / MP / EN / shield の現在値と最大値を参照する。
- `class`: 現在クラスの表示名とクラスレベルを参照する。
- `player-setting`: performance 情報の表示可否を参照する。
- `world`: ワールド表示名、地域名、地域レベルを解決する。
- `boss`: 挑戦中ボスの sidebar 情報を参照する。
- `player` の dodge / air action と、primary action bar を差し替える feature は ActionBar の優先順位を共有する。

## 更新ルール（変更時に必ず更新する章）

- 更新周期・対象モード・表示優先順位変更: [[10_3.02-サービス]]、[[10_4.00-統合フロー]]
- ActionBar、vanilla bar、sidebar、tab list の表示項目変更: [[10_2.00-ユースケース]]、[[10_3.07-View]]
- dodge / wall / primary renderer の上書き契約変更: [[10_3.02-サービス]]、[[10_4.00-統合フロー]]
- 例外時の復旧・停止処理変更: [[10_5.00-例外・ログ・運用]]
