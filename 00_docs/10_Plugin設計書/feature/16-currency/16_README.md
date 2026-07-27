# 16_README

`currency` feature は、inventory feature が保持する `CURRENCY` インベントリを通貨表示・ゴールド換算・額面交換として利用する facade を提供する。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/currency/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`

## ドキュメント一覧（推奨順）

1. [[16_0.00-概要]]
2. [[16_1.00-モデル定義]]
3. [[16_2.00-ユースケース]]
4. [[16_3.00-メソッド仕様]]
5. [[16_4.00-統合フロー]]
6. [[16_5.00-例外・ログ・運用]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| inventory | `CURRENCY` の entry、スナップショット、増減、保存 |
| item | 通貨 item 定義、表示用 `ItemStack`、カテゴリ判定 |
| menu | 通貨一覧 GUI、通常 BAG との移動、両替所への導線 |
| mob / mail / shop / trade / world | 報酬付与、支払い、プレイヤー間移転、帰還コストの呼び出し元 |

## 更新ルール（変更時に必ず更新する章）

- 組み込みゴールド額面、換算値、互換 ID を変更した場合は [[16_0.00-概要]]、[[16_1.00-モデル定義]]、[[16_3.00-メソッド仕様]]を更新する。
- 通貨 GUI の移動量、ページング、両替導線を変更した場合は [[16_2.00-ユースケース]]、[[16_4.00-統合フロー]]を更新する。
- `CURRENCY` の保存・増減・補償処理を変更した場合は inventory feature と [[16_3.00-メソッド仕様]]、[[16_5.00-例外・ログ・運用]]を更新する。
- 呼び出し元の支払い・報酬契約を変更した場合は、該当 feature の設計書も更新する。
