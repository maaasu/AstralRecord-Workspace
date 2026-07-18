# 19_README

このディレクトリは `feature/party` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/party/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/party/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/party/gui/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/party/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/party/model/*`

## ドキュメント一覧（推奨順）

1. [[19_0.00-概要]]
2. [[19_1.00-モデル定義]]
3. [[19_2.00-ユースケース]]
4. [[19_3.00-メソッド仕様]]
5. [[19_4.00-統合フロー]]
6. [[19_5.00-例外・ログ・運用]]

## 依存 feature

- `player`
  - オンラインプレイヤーと `AstPlayer` をメンバー情報として扱う。
- `menu`
  - パーティー GUI の導線元。menu は戻る遷移のみを担当する。
- `mob`
  - Mob 撃破時のパーティーメンバー報酬対象判定で参照される。

## 更新ルール（変更時に必ず更新する章）

- パーティーメンバー状態・招待状態の変更:
  - [[19_1.00-モデル定義]]
  - [[19_3.00-メソッド仕様]]
- GUI 構成・戻る遷移の変更:
  - [[19_3.00-メソッド仕様]]
  - [[19_4.00-統合フロー]]
- 報酬共有条件の変更:
  - [[19_2.00-ユースケース]]
  - mob feature の撃破処理
