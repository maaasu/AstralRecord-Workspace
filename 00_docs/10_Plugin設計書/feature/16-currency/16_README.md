# 16_README

このディレクトリは `feature/currency` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/currency/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/currency/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/currency/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/currency/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/currency/gui/*`

## ドキュメント一覧（推奨順）

1. [[16_0.00-概要]]
2. [[16_1.00-モデル定義]]
3. [[16_2.00-ユースケース]]
4. [[16_3.00-メソッド仕様]]
5. [[16_4.00-統合フロー]]
6. [[16_5.00-例外・ログ・運用]]

## 依存 feature

- `account`
  - 通貨残高はアカウント単位で扱う。
- `item`
  - 通貨表示用 ItemStack の見た目と `currency` カテゴリ item 表現を参照する。
- `menu`
  - 通貨 GUI の導線元。menu は表示導線のみを担当する。
- `mob`
  - Mob 撃破時の金銭加算で本 feature を呼び出す。
- `mail`
  - メール既読時報酬で通貨加算を行う。

## 更新ルール（変更時に必ず更新する章）

- 通貨種別・残高モデルの変更:
  - [[16_1.00-モデル定義]]
  - [[16_3.00-メソッド仕様]]
- 通貨加算・減算・表示順の変更:
  - [[16_2.00-ユースケース]]
  - [[16_4.00-統合フロー]]
- 他 feature からの呼び出し契約変更:
  - [[16_3.00-メソッド仕様]]
  - 呼び出し元 feature の README
