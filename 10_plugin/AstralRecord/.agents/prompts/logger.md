# Plugin Logger Prompt

## 読むタイミング

- ログメッセージを追加・変更するとき
- `LogId` を追加・変更するとき
- `logger.properties` を更新するとき

## 必須ルール

- コードへ直接ログ文言を書かない。
- 既存の logger ラッパーと `LogId` を使う。
- 新しいログを追加する場合は、メッセージ定義と ID をセットで更新する。

## 更新チェックリスト

1. `logger.properties` にメッセージを追加・修正する。
2. `LogId` に対応する ID を追加・修正する。
3. コード側は既存の logger API 経由で呼ぶ。
4. 例外を扱うログでは Throwable を失っていないか確認する。

## 非推奨

- 文字列直書きログ
- `printStackTrace()` だけで済ませること
- 既存 ID と意味が重複する新規 ID の追加
