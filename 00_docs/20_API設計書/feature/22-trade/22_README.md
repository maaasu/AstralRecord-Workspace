# 22-trade API 設計

## 対象実装

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/TradeController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/TradeModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/ITradeRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/TradeRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/TradeCommitEntity.cs`

## 対応 Plugin feature

- [[22_0-概要]]（Plugin）のトレード確定処理

## ドキュメント

1. [[22_0.00-概要（API）]]
2. [[22_1.00-モデル定義（API）]]
3. [[22_3.00-登録系（API）]]

## 依存 feature

- 13-inventory: GAME BAG/HOTBAR と CURRENCY inventory
- 14-equipment: 装備個体 owner の移管。ルーンは inventory の通常スタック数量として移管

## 更新ルール

- リクエスト、レスポンス、ステータスコードを変える場合は [[22_1.00-モデル定義（API）]] と [[22_3.00-登録系（API）]] を更新する。
- transaction の対象、冪等性、所有権移管を変える場合は DB の `dbo.trade_commit` 定義も更新する。
