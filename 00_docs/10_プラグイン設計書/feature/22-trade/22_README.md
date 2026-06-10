# 22_README

このディレクトリは `feature/trade` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/trade/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/trade/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/trade/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/trade/gui/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/trade/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/trade/service/*`

## ドキュメント一覧（推奨順）

1. [[22_0.00-概要]]
2. [[22_1.00-モデル定義]]
3. [[22_2.00-ユースケース]]
4. [[22_3.00-メソッド仕様]]
5. [[22_4.00-統合フロー]]
6. [[22_5.00-例外・ログ・運用]]
7. [[22_9.00-未決事項]]

## 依存 feature

- `player`
  - 申請者・相手プレイヤーのオンライン状態、表示名、メッセージ送信先を参照する。
- `inventory`
  - 取引成立時のアイテム付与、空きスロット検証、取引 GUI から戻すアイテムの返却に利用する。
- `item`
  - AstralRecord ItemStack 判定、取引不可アイテム判定、表示用 ItemStack 生成に利用する。
- `menu`
  - 既存 GUI の戻るボタン位置とダミーアイテム表現に合わせる。

## 更新ルール（変更時に必ず更新する章）

- 取引セッション状態・申請期限・準備状態の変更:
  - [[22_1.00-モデル定義]]
  - [[22_3.00-メソッド仕様]]
  - [[22_4.00-統合フロー]]
- GUI レイアウト・ボタン挙動の変更:
  - [[22_1.00-モデル定義]]
  - [[22_3.00-メソッド仕様]]
  - [[22_4.00-統合フロー]]
- コマンド・クリック可能メッセージの変更:
  - [[22_2.00-ユースケース]]
  - [[22_3.00-メソッド仕様]]
  - [[22_5.00-例外・ログ・運用]]
- 取引不可アイテム判定の変更:
  - [[22_1.00-モデル定義]]
  - item feature の取引可否定義
