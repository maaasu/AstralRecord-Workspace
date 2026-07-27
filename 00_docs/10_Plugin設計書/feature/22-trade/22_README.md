# 22_README

`trade` feature は online player 間の 60 秒招待と、item／gold を一時 escrow する GUI 取引を提供する。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/trade/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/gui/gold/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/*`

## ドキュメント一覧（推奨順）

1. [[22_0.00-概要]]
2. [[22_1.00-モデル定義]]
3. [[22_2.00-ユースケース]]
4. [[22_3.00-メソッド仕様]]
5. [[22_4.00-統合フロー]]
6. [[22_5.00-例外・ログ・運用]]
7. [[22_9.00-未決事項]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| player | `AccountMode.PLAYER`、online identity、通知 |
| item | `unTradeable` 判定と item identity |
| inventory / currency | offer withdraw、gold 残高、snapshot／restore／保存 |
| menu / shared GUI | 取引 GUI、cancel confirm、gold amount setting |

## 更新ルール（変更時に必ず更新する章）

- request TTL／status を変更した場合は [[22_1.00-モデル定義]]、[[22_2.00-ユースケース]]を更新する。
- escrow、commit、rollback を変更した場合は [[22_3.00-メソッド仕様]]、[[22_4.00-統合フロー]]、[[22_5.00-例外・ログ・運用]]を更新する。
- GUI layout／close 遷移を変更した場合は [[22_1.00-モデル定義]] と [[22_4.00-統合フロー]]を更新する。
- durable recovery を実装した場合は [[22_9.00-未決事項]]を解消する。
