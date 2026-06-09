# 18_README

このディレクトリは `feature/mail` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/mail/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mail/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mail/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mail/gui/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mail/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mail/repository/*`

## ドキュメント一覧（推奨順）

1. [[18_0.00-概要]]
2. [[18_1.00-モデル定義]]
3. [[18_2.00-ユースケース]]
4. [[18_3.00-メソッド仕様]]
5. [[18_4.00-統合フロー]]
6. [[18_5.00-例外・ログ・運用]]

## 依存 feature

- `account`
  - 初回参加者向け `welcome_mail` の配布導線から呼び出される。
- `currency`
  - 既読時報酬として通貨を加算する。
- `item` / `inventory`
  - 添付アイテムがある場合の表示・付与に利用する。
- `menu`
  - メール GUI を開く導線元。

## 更新ルール（変更時に必ず更新する章）

- メールモデル・既読状態の変更:
  - [[18_1.00-モデル定義]]
  - [[18_3.00-メソッド仕様]]
- 報酬配布仕様の変更:
  - [[18_2.00-ユースケース]]
  - [[18_4.00-統合フロー]]
  - [[18_5.00-例外・ログ・運用]]
- GUI 構成変更:
  - [[18_3.00-メソッド仕様]]
  - [[18_4.00-統合フロー]]
