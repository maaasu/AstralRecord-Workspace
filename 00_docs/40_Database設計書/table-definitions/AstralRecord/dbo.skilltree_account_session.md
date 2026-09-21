# dbo.skilltree_account_session

アカウントの保存・Web確定を実行できる参加先を一つに限定する永続lease台帳。終了履歴も残し、期限切れや閉鎖したIDを復活させない。

| カラム | 意味 |
|:--|:--|
| account_session_id | 主キー。参加ごとのランダムUUID |
| account_id | 所有account |
| server_id / server_session_id | 所有serverとboot |
| definition_generation_id | 参加時の定義世代。reload後の退出flushでもこの世代を使用 |
| lease_token_hash | 32byte秘密tokenのSHA-256。平文tokenは保存しない |
| created_at_utc / expires_at_utc | 生成・期限。45秒ごとではなくheartbeat/viewで期限を延長 |
| closed | 終了済み。期限切れの行も新session取得時に終了扱いにする |
| view_sequence | 最新の受理view番号。遅延した位置通知を拒否 |

`UX_skilltree_account_session_active`は`closed = 0`のaccount_idを一意にする。APIはaccount行ロックの下で所有権更新と旧操作失効を実行する。保持中stateがあるaccountの保存にはこのleaseが必要で、snapshotのsection種類によらない。
