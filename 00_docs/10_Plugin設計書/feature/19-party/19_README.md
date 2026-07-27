# 19_README

`party` feature はオンライン中だけ有効な最大 6 人のパーティー、招待、リーダー操作、GUI／コマンド／チャット連携を提供する。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/party/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java`

## ドキュメント一覧（推奨順）

1. [[19_0.00-概要]]
2. [[19_1.00-モデル定義]]
3. [[19_2.00-ユースケース]]
4. [[19_3.00-メソッド仕様]]
5. [[19_4.00-統合フロー]]
6. [[19_5.00-例外・ログ・運用]]
7. [[19_9.00-未決事項]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| player / user | `AccountMode.PLAYER` guard、通知、ユーザー履歴 |
| menu / gui | パーティー一覧・メンバー操作 GUI |
| chat | `/party chat` の party 宛配信 |
| mob | 討伐報酬の party member 展開と距離判定 |

## 更新ルール（変更時に必ず更新する章）

- party／invite の保持構造、上限、lifecycle を変更した場合は [[19_1.00-モデル定義]] と [[19_5.00-例外・ログ・運用]]を更新する。
- 招待、脱退、権限操作を変更した場合は [[19_2.00-ユースケース]]、[[19_3.00-メソッド仕様]]、[[19_4.00-統合フロー]]を更新する。
- 報酬共有距離・対象条件を変更した場合は mob feature と [[19_4.00-統合フロー]]を更新する。
- 未実装の招待有効期限を決定した場合は [[19_9.00-未決事項]]を解消し、実装・運用章へ反映する。
