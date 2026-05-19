# Plugin Player Message Prompt

## 読むタイミング

- プレイヤー向けメッセージを追加・変更するとき
- `MsgId` を追加・変更するとき
- `player.properties` を更新するとき

## 必須ルール

- コードへ直接メッセージ文言を書かない。
- プレイヤー通知は既存のメッセージ管理を経由する。
- `player.properties` と `MsgId` はセットで更新する。
- `sendInfo/sendSuccess/sendError/sendMessage` に文字列リテラルを直接渡さない。

## 更新チェックリスト

1. `player.properties` にメッセージを追加・修正する。
2. `MsgId` に対応する ID を追加・修正する。
3. `PlayerMsgResource` や `AstPlayer.sendMessage(...)` 経由で呼ぶ。
4. 色コード、プレースホルダ、既存の文体が揃っているか確認する。
5. 変更ファイルに対して `sendInfo(` `sendSuccess(` `sendError(` の引数がメッセージID経由になっているか最終確認する。

## 非推奨

- メッセージ文字列の直書き
- `MsgId` だけ、または `player.properties` だけの片側更新
- 既存メッセージの意味を利用箇所確認なしで変えること
