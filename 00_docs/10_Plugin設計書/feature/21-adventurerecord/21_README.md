# 21_README

`adventurerecord` feature は account 単位の ENEMY／BOSS 討伐記録を API へ保存し、討伐済み Mob と drop item 検索結果を GUI に表示する。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/adventurerecord/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/*`

## ドキュメント一覧（推奨順）

1. [[21_0.00-概要]]
2. [[21_1.00-モデル定義]]
3. [[21_2.00-ユースケース]]
4. [[21_3.00-メソッド仕様]]
5. [[21_4.00-統合フロー]]
6. [[21_5.00-例外・ログ・運用]]
7. [[21_9.00-未決事項]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| infrastructure / API | Mob 討伐記録の取得・加算 |
| mob | MobTemplate、category、drop 定義、討伐確定 |
| player / player-setting | account／user identity、super mode |
| item / menu | drop item 検索 slot と GUI navigation |

## 更新ルール（変更時に必ず更新する章）

- API record schema／endpoint を変更した場合は [[21_1.00-モデル定義]]、[[21_3.00-メソッド仕様]] と API 設計書を更新する。
- 一覧種別、検索、super mode を変更した場合は [[21_2.00-ユースケース]] と [[21_4.00-統合フロー]]を更新する。
- 討伐記録の対象・呼出位置を変更した場合は mob feature と [[21_3.00-メソッド仕様]]を更新する。
- 絆の記録を実装した場合は [[21_9.00-未決事項]]を解消する。
