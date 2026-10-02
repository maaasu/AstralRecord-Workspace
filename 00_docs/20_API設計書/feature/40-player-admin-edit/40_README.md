# 40_player-admin-edit

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/PlayerAdminController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/PlayerAdminEditModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/PlayerAdminEditRepository*`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/PlayerAdmin*`
- 通常snapshot・account session取得・network admissionの管理編集制限

## 対応Plugin・Web

- [[40_0-概要]]：ゲーム退避・保存完了確認
- [[09_0-プレイヤー管理編集]]：Web管理画面

## ドキュメント一覧

1. [[40_0.00-概要]]
2. [[40_1.00-モデル定義]]
3. [[40_3.00-エンドポイント仕様]]
4. [[40_5.00-例外・ログ・運用]]

## 依存feature

- アカウント・inventory・装備個体・クラス進行
- [[34_0.00-概要]]：player-state snapshotとaccount session
- [[33_0.00-概要]]：参加判定
- Web管理ロール・Management DB監査

## 更新ルール

API契約・状態遷移・ロック順序・snapshot許可段階の変更では、モデル・エンドポイント・運用章とPlugin/Web/DB設計を同時に更新する。マスタ版の算出とオフライン計算を変更する場合は、Plugin参照値を含むテストも更新する。
