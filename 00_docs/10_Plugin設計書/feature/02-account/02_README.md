# 02_README

このディレクトリは `feature/account` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/model/*`

## ドキュメント一覧（推奨順）

1. [[02_0.00-概要]]
2. [[02_1.00-モデル定義]]
3. [[02_2.00-ユースケース]]
4. [[02_3.00-索引]]
5. [[02_3.01-サービス]]
6. [[02_3.02-コマンド]]
7. [[02_3.03-リポジトリ]]
8. [[02_4.00-統合フロー]]
9. [[02_5.00-例外・ログ・運用]]
10. [[02_9.00-未決事項]]（必要時）

## 依存 feature

- `user`
  - [[01_3.02-サービス]].新規ユーザ登録 からアカウント作成が呼び出される。
  - [[01_1.00-モデル定義]].ユーザモデル の `accountId` と選択アカウントを同期する。
- `player`
  - オンライン中の [[03_1.00-モデル定義]].プレイヤーセッション へアカウントモード変更を反映する。
- `inventory`
  - アカウントモードが通常プレイヤーの場合、GUIインベントリ反映を依頼する。

## 更新ルール（変更時に必ず更新する章）

- アカウント作成・切替・モード更新の処理順変更:
  - [[02_3.01-サービス]]
  - [[02_4.00-統合フロー]]
  - [[02_3.03-リポジトリ]]（API 入出力が変わる場合）
- [[02_1.00-モデル定義]].アカウントモデル 項目追加・削除:
  - [[02_1.00-モデル定義]]
  - [[02_3.03-リポジトリ]]
- コマンド仕様変更（`/account` 系）:
  - [[02_3.02-コマンド]]
  - [[02_5.00-例外・ログ・運用]]（運用影響がある場合）
