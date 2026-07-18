# 21_README

このディレクトリは `feature/adventurerecord` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/adventurerecord/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/adventurerecord/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/adventurerecord/gui/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/adventurerecord/model/*`

## ドキュメント一覧（推奨順）

1. [[21_0.00-概要]]
2. [[21_1.00-モデル定義]]
3. [[21_2.00-ユースケース]]
4. [[21_3.00-メソッド仕様]]
5. [[21_4.00-統合フロー]]
6. [[21_5.00-例外・ログ・運用]]

## 依存 feature

- `player`
  - 表示対象プレイヤーの基本情報を参照する。
- `account`
  - 冒険記録の所有単位として account を参照する。
- `menu`
  - 冒険記録 GUI の導線元。

## 更新ルール（変更時に必ず更新する章）

- 記録モデル・集計項目の変更:
  - [[21_1.00-モデル定義]]
  - [[21_3.00-メソッド仕様]]
- GUI 構成変更:
  - [[21_3.00-メソッド仕様]]
  - [[21_4.00-統合フロー]]
- 他 feature からの記録更新契約変更:
  - [[21_2.00-ユースケース]]
  - 呼び出し元 feature の README
