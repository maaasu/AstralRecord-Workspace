# 01_README

このディレクトリは `feature/user` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/user/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/user/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/user/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/user/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/user/model/*`

## ドキュメント一覧（推奨順）

1. [[01_0.00-概要]]
2. [[01_1.00-モデル定義]]
3. [[01_2.00-ユースケース]]
4. [[01_3.00-索引]]
5. [[01_4.00-統合フロー]]
6. [[01_5.00-例外・ログ・運用]]
7. [[01_9.00-未決事項]]（必要時）

## 依存 feature

- `account`
  - 新規ユーザー登録時に初期アカウント作成を委譲する。
- `player`
  - [[03_1.00-モデル定義]].プレイヤーキャッシュ 経由でオンラインプレイヤーの権限反映を行う。

## 更新ルール（変更時に必ず更新する章）

- ユーザー登録・ログイン更新の処理順変更:
  - [[01_3.02-サービス]]
  - [[01_4.00-統合フロー]]
  - [[01_3.04-リポジトリ]]（API 入出力が変わる場合）
- [[01_1.00-モデル定義]].ユーザモデル 項目追加・削除:
  - [[01_1.00-モデル定義]]
  - [[01_3.04-リポジトリ]]
- コマンド仕様変更（`/user` 系）:
  - [[01_3.03-コマンド]]
  - [[01_3.02-サービス]]（サービス呼び出しが変わる場合）
  - [[01_5.00-例外・ログ・運用]]（運用影響がある場合）
- ログIDや障害対応手順の変更:
  - [[01_5.00-例外・ログ・運用]]
  - [[01_9.00-未決事項]]（未確定事項がある場合）
