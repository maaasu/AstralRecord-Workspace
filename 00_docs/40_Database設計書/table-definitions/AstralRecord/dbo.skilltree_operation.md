# dbo.skilltree_operation

Web編集要求の冪等台帳です。`operation_id` と `request_hash` により再送の二重課金を防止します。

要求は対象server、定義世代、スキルツリーstate version、`evaluation_fingerprint`を固定して登録する。Pluginは同一server sessionかつ未期限leaseをclaimし、player-state snapshotと同一serializable transactionでAPPLIEDを確定する。Offline案は`PENDING_OFFLINE`として保存され、参加先が同じgeneration・state version・fingerprintを再検証できないと`RECONFIRMATION_REQUIRED`または`CANCELED`となる。

`PENDING_*`、`CLAIMED`、`APPLIED`、`RECONFIRMATION_REQUIRED`、`FAILED`、`CANCELED`、`EXPIRED`を区別し、予約完了を適用済みと扱わない。

`claimed_account_session_id`は処理権限を取得した参加session。確定時にboot、account session、30秒claim leaseと7日期限を再照合する。APPLIEDはplayer-state transactionと同時記録する。有効操作はaccount行ロック下で1件に制限し、取消/失効を過去操作の再送で復活させない。

`changes_json NVARCHAR(MAX) NULL`はBATCH全体の順序付き変更配列。従来UNLOCK/RELOCKはNULL、BATCHはnode_id=batchかつ非NULLのJSONを要求する。operation_id/request_hash/lease/statusはbatch全体に一つだけ保持し、部分適用台帳は設けない。導入は`20260921_skilltree_batch_editor.sql`をsafe_editorの後に実行する。
