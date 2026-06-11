# 23_README

このディレクトリは `feature/market` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

Plugin 側の market feature は、ゲーム内 GUI やコマンドから利用する API クライアント層を提供する。
出品可否、価格ガード、相場算出、購入確定などの基本ロジックは [[23_README]]（API 側）を正本とし、Plugin は API 呼び出しと短時間キャッシュを担当する。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/market/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/market/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/market/service/*`

## ドキュメント一覧（推奨順）

1. [[23_0.00-概要]]
2. [[23_1.00-モデル定義]]
3. [[23_3.00-メソッド仕様]]
4. [[23_4.00-統合フロー]]
5. [[23_5.00-例外・ログ・運用]]

## 依存 feature

- `inventory`
  - 出品元 entry や購入後の表示更新に利用する。最終移転は API が行う。
- `item`
  - 表示用 ItemStack 生成に利用する。価格判定は API に委譲する。
- `currency`
  - 通貨表示に利用する。通貨残高の正本化は API 側未決事項に従う。
- `menu`
  - 将来の GUI 導線元。

## 更新ルール（変更時に必ず更新する章）

- API 契約変更:
  - [[23_1.00-モデル定義]]
  - [[23_3.00-メソッド仕様]]
- キャッシュ TTL / 破棄条件変更:
  - [[23_0.00-概要]]
  - [[23_3.00-メソッド仕様]]
  - [[23_4.00-統合フロー]]
- GUI / コマンド追加:
  - 該当する `3-メソッド仕様` の詳細ファイルを追加する。
