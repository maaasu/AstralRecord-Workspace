# AstralRecordWeb Guide

対象: `30_web/AstralRecordWeb/AstralRecordWeb/`

## 役割

- 管理・公開用の Web UI を管理する。
- API を利用して管理画面や公開画面を構成する。

## ディレクトリ方針

- Razor Pages は `Pages/` に置く。
- `.cshtml` と `.cshtml.cs` はセットで管理する。
- 画面ごとの責務をページ単位で閉じる。

## 実装方針

- Razor Pages の Page Model パターンを守る。
- 既存の UI、レイアウト、ナビゲーション構造に合わせる。
- API 依存がある画面では契約変更の影響を確認する。

## ドキュメント運用

- 大きな導線変更や運用変更があれば、必要に応じて README や関連資料も更新する。

## 補助プロンプト

- `.agents/prompts/pages.md`
  - Razor Pages 追加・変更時の確認観点と更新手順を扱う。
