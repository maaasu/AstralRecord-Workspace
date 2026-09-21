# dbo.skilltree_server_runtime

各ゲームサーバーが実際にロードして公開しているスキルツリー世代とsessionを保持する一行台帳です。

| カラム | 説明 |
|:--|:--|
| `server_id` | サーバー識別子（主キー） |
| `server_session_id` / `server_started_at_utc` | 起動sessionと旧boot遅延registerを拒否する単調fence |
| `plugin_version` / `compatibility_version` | 実行Pluginの互換性情報 |
| `definition_generation_id` | 成功して公開した定義世代 |
| `ready` / `last_seen_utc` | 準備完了とheartbeat最終時刻 |

reload失敗時は新世代・readyを更新しない。heartbeat TTLを越えた行は現在世代の保証に使わない。
