# 防衛戦管理テーブル

正本DDLは [20261019_defense.sql](migrations/20261019_defense.sql) と [init.sql](init.sql)。通常のゲームDBリセットから独立して、設定、開催、参加、段階報酬を保持する。ゲームDBのaccountには外部キーを張らず、報酬配送時に本人と現存アカウントを再確認する。

| テーブル | 主キー・一意性 | 内容 |
|---|---|---|
| `defense_channel` | `server_id` | revision、JST日次設定、調整値JSON、runtime/受付の最終報告。Web更新はrevision比較 |
| `defense_run` | `run_id`、unique `operation_id`、active時の`server_id`一意、日次の`server_id,scheduled_date_jst`一意 | 手動/日次、PENDINGから終端までの状態、時刻、起動世代、開催固定設定JSON、最高報告レベル、人数、理由 |
| `defense_participant` | `run_id,account_id`、unique `run_id,user_uuid` | STARTEDで固定した本人/アカウント。進行報告による活動秒・AFK・脱落 |
| `defense_reward` | `run_id,account_id,defense_level`、unique `mail_id` | 到達段階、固定されたorb ID/数量、確定/メール配送日時 |
| `defense_report` | `operation_id` | STOP、STARTED、HEARTBEAT、ENDED、ABORTED、REWARDの冪等ハッシュ |

`defense_run.configuration_json`は作成時の設定スナップショットで、途中のWeb変更では更新しない。active runの一意なfiltered indexが重複起動を防ぎ、日次の一意なfiltered indexが時刻変更や再送でも同じJST日付を再発行しない。`defense_reward` の段階一意キーは、別チャンネルの後続runの報酬を妨げない。

報酬保存とゲームDBメール配送は別DBにまたがる。まずManagementDBの段階報酬を確定し、その後固定`mail_id`の `AstralRecord.dbo.player_mail_delivery` を登録し、配達日時を記す。途中停止後も同じメールIDで再試行し、受取は既存のメール/プレイヤー保存境界で行う。アカウントが削除されていた場合も確定台帳を消さない。ManagementDBのみを過去時点へ戻すとゲームDBとの一意性が崩れるため、バックアップ・復元はDB間の時点を照合する。
