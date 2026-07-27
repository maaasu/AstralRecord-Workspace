# 30_README

Java Edition プレイヤーへのサーバーリソースパック要求と、クライアント結果の追跡を扱う。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/resourcepack/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/infrastructure/config/ConfigKeys.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/infrastructure/config/ConfigProperties.java`
- `10_plugin/AstralRecord/src/main/resources/config.yml`
- `10_plugin/AstralRecord/src/main/resources/player.properties`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`

## ドキュメント一覧（推奨順）

1. [[30_0.00-概要]]
2. [[30_1.00-モデル定義]]
3. [[30_2.00-ユースケース]]
4. [[30_3.00-メソッド仕様]]
5. [[30_4.00-統合フロー]]
6. [[30_5.00-例外・ログ・運用]]

## 依存 feature

- [[03_README]]: プレイヤー参加ライフサイクルとプレイヤー向けメッセージ

## 更新ルール（変更時に必ず更新する章）

- 設定キー、既定値、pack ID の導出規則を変更した場合は、モデル定義と運用を更新する。
- 送信条件、Bedrock 判定、status 分岐を変更した場合は、メソッド仕様と統合フローを更新する。
- メッセージ ID またはログ ID を変更した場合は、例外・ログ・運用を更新する。
