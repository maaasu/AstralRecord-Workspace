# 23_README

このディレクトリは API `market` 機能の設計書です。
採番・命名・参照ルールは [[README]] に従います。

マーケットの出品、購入、キャンセル、相場算出、価格ガード、アカウント単位の出品数制限を扱う DB 書き込み系 API。
Web サイトと Plugin は同じ API 契約を利用し、マーケットの基本ロジックは API に集約する。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/MarketController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/MarketModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IMarketRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/MarketRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Services/MarketPriceService.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Services/MarketListingLimitService.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/MarketListing.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/MarketListingSource.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/MarketTransaction.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/MarketPriceSnapshot.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/MarketAccountState.cs`

## 対応プラグイン feature

- Plugin 側に専用 feature を追加する場合は `23-market` として採番する。
- Plugin は GUI 操作、通知、アイテム表示のみを担当し、出品可否・価格判定・購入確定は API に委譲する。
- Web は一覧、検索、相場確認、出品、購入、キャンセル、売上受取を同じ API から実行する。

## ドキュメント一覧

1. [[23_0.00-概要]]
2. [[23_1.00-モデル定義]]
3. [[23_2.00-ユースケース]]
4. [[23_3.00-索引]]
5. [[23_3.01-取得系]]
6. [[23_3.02-登録系]]
7. [[23_3.03-更新系]]
8. [[23_4.00-統合フロー]]
9. [[23_5.00-例外・ログ・運用]]
10. [[23_9.00-未決事項]]

## 依存 feature

- `account`: 出品者・購入者・出品制限はアカウント単位で扱う。
- `inventory`: 出品時の所持品確認、スタック品の数量確保、キャンセル時の返却先。
- `equipment`: 装備個体の所有者、強化値、ランダムステータス、エンチャント、ルーン装着状態を価格評価に使う。
- `rune`: ルーン個体の所有者、ランダムステータスを価格評価に使う。
- `item`: `itemId`、カテゴリ、店売り価格、基本価格の参照元。
- `currency`: 購入時の支払い、売上入金、手数料控除を API 内部サービスとして扱う。

## 更新ルール

- エンドポイント追加・削除: [[23_3.00-索引]]
- DTO 変更: [[23_1.00-モデル定義]] + 該当エンドポイント
- 相場算出・価格ガード変更: [[23_0.00-概要]] / [[23_4.00-統合フロー]] / [[23_5.00-例外・ログ・運用]]
- 出品数制限変更: [[23_0.00-概要]] / [[23_1.00-モデル定義]] / [[23_4.00-統合フロー]]
- DB スキーマ変更: `00_docs/40_Database設計書/table-definitions/AstralRecord/dbo.market_*.md`
