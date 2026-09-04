# 33-network

## 対象実装パス

- `AstralRecordApi/Controllers/NetworkController.cs`
- `AstralRecordApi/Models/NetworkModels.cs`
- `AstralRecordApi/Services/INetworkRuntimeService.cs`
- `AstralRecordApi/Services/NetworkRuntimeService.cs`

## 対応プラグインfeature

[[33_0-概要]]

## ドキュメント一覧

1. [[33_0.00-概要]]
2. [[33_1.00-モデル定義]]
3. [[33_3.00-エンドポイント仕様]]

## 依存feature

- `01-user`: UUIDに対するBAN、permission、選択アカウント参照

## 更新ルール

Network Controllerのpath、DTO、TTL、チャット保持数を変更した場合は本featureを同時更新する。
