# dbo.skilltree_operation

Web編集要求の冪等台帳です。`operation_id` と `request_hash` により再送の二重課金を防止します。

要求は対象server、定義世代、スキルツリーstate version、`evaluation_fingerprint`を固定して登録する。Pluginは同一server sessionかつ未期限leaseをclaimし、player-state snapshotと同一serializable transactionでAPPLIEDを確定する。Offline案は`PENDING_OFFLINE`として保存され、参加先が同じgeneration・state version・fingerprintを再検証できないと`RECONFIRMATION_REQUIRED`または`CANCELED`となる。

`PENDING_*`、`CLAIMED`、`APPLIED`、`RECONFIRMATION_REQUIRED`、`FAILED`、`CANCELED`、`EXPIRED`を区別し、予約完了を適用済みと扱わない。
