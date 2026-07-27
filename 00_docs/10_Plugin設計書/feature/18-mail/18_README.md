# 18_README

`mail` feature は API 上の表示可能メールを取得し、GUI での既読化、受取時報酬付与、プレイヤー単位の削除を扱う。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mail/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java`

## ドキュメント一覧（推奨順）

1. [[18_0.00-概要]]
2. [[18_1.00-モデル定義]]
3. [[18_2.00-ユースケース]]
4. [[18_3.00-メソッド仕様]]
5. [[18_4.00-統合フロー]]
6. [[18_5.00-例外・ログ・運用]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| infrastructure / API | `/api/mail` の一覧、既読化、削除 |
| item | 報酬 item 定義の解決、equipment / rune instance の事前生成 |
| inventory | NORMAL inventory への一括付与と rollback receipt |
| player / menu | user・account identity、通知、GUI navigation |

## 更新ルール（変更時に必ず更新する章）

- API の mail response／action を変更した場合は [[18_1.00-モデル定義]]、[[18_3.00-メソッド仕様]] と API 設計書を更新する。
- 受取・rollback・reconciliation を変更した場合は [[18_4.00-統合フロー]] と [[18_5.00-例外・ログ・運用]]を更新する。
- GUI の click、filter、page を変更した場合は [[18_2.00-ユースケース]] と [[18_4.00-統合フロー]]を更新する。
- welcome mail 等の発行規則は API／master data 側に記載し、プラグインだけの仕様として追加しない。
