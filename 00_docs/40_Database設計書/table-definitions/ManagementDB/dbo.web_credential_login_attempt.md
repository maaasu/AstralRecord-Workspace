# dbo.web_credential_login_attempt テーブル定義

固定ログインID単位の失敗ログイン回数をゲームDBから独立して保持する。

| カラム | 型 | NULL | 内容 |
|---|---|---|---|
| `login_id` | `NVARCHAR(64)` | NO | 正規化済み固定ログインID。主キー |
| `failed_attempts` | `INT` | NO | 現在の15分ウィンドウにおける失敗回数 |
| `window_started_at_utc` | `DATETIME2(3)` | NO | 失敗回数ウィンドウの開始時刻 |
| `locked_until_utc` | `DATETIME2(3)` | YES | 10回失敗後のログイン拒否終了時刻 |
| `revision` | `INT` | NO | 楽観ロック用の更新版 |

失敗更新は`revision`を使って競合時に再試行する。成功ログインでは当該IDの記録を削除する。ID未登録・無効・パスワード不正は同じ失敗として記録し、外部へは区別しない。
