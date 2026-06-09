# 20_README

このディレクトリは `feature/shop` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/shop/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/shop/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/shop/gui/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/shop/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/shop/model/*`

## ドキュメント一覧（推奨順）

1. [[20_0.00-概要]]
2. [[20_1.00-モデル定義]]
3. [[20_2.00-ユースケース]]
4. [[20_3.00-メソッド仕様]]
5. [[20_4.00-統合フロー]]
6. [[20_5.00-例外・ログ・運用]]

## 依存 feature

- `currency`
  - 購入時の支払いを委譲する。
- `item` / `inventory`
  - 商品 ItemStack 表示と購入品付与に利用する。
- `mob`
  - NPC interaction からショップを開く導線元。
- `menu`
  - 戻る遷移や共通 GUI 操作の参照元。

## 更新ルール（変更時に必ず更新する章）

- 商品モデル・価格仕様の変更:
  - [[20_1.00-モデル定義]]
  - [[20_3.00-メソッド仕様]]
- 購入・売却・在庫処理の変更:
  - [[20_2.00-ユースケース]]
  - [[20_4.00-統合フロー]]
- NPC 導線の変更:
  - [[20_4.00-統合フロー]]
  - mob feature の NPC interaction 記述
