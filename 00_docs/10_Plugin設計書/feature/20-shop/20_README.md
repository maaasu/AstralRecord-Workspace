# 20_README

`shop` feature は filebase/API 由来の shop・recipe 定義を cache し、command／NPC から購入 GUI を開き、gold・通貨・通常 item を対価に商品を付与する。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/shop/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/currency/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`

## ドキュメント一覧（推奨順）

1. [[20_0.00-概要]]
2. [[20_1.00-モデル定義]]
3. [[20_2.00-ユースケース]]
4. [[20_3.00-メソッド仕様]]
5. [[20_4.00-統合フロー]]
6. [[20_5.00-例外・ログ・運用]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| infrastructure / filebase | shop master の一覧取得と cache |
| filebase / shop repository | `recipeId` の追加 gold・ingredient cost |
| item | 商品・必要 item の model 解決と表示名 |
| inventory / currency | 所持数、支払い、商品付与、snapshot／保存 |
| mob / npc / menu | NPC 導線と GUI navigation |

## 更新ルール（変更時に必ず更新する章）

- shop／entry／recipe schema を変更した場合は [[20_1.00-モデル定義]]、filebase 設計書、parser を更新する。
- 購入 validation・補償順序を変更した場合は [[20_3.00-メソッド仕様]]、[[20_4.00-統合フロー]]、[[20_5.00-例外・ログ・運用]]を更新する。
- command／NPC access を変更した場合は [[20_2.00-ユースケース]] と [[20_3.00-メソッド仕様]]を更新する。
- 在庫・販売回数制限を導入する場合は model と永続化を設計してから仕様へ追加する。
