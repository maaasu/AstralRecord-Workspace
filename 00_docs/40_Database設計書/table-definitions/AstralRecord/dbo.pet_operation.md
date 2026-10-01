# dbo.pet_operation

ペット操作の原子性と再送重複防止。`operation_id UNIQUEIDENTIFIER` がPK、`account_id UNIQUEIDENTIFIER` はFK→account.uuid。`request_hash CHAR(64)`、`response_json NVARCHAR(MAX)`、`affected_entry_ids_json NVARCHAR(MAX)`、`completed_at DATETIME2(3)` は非NULL。JSON列にISJSON制約。

`IX_pet_operation_owner_completed(account_id,completed_at)`。operationIdはAPI全体で一意。種類・対象個体・要求本文を含むSHA-256を照合し、異なる要求での再利用は拒否。確定済み操作の素材消費/個体作成を再実行しない。応答と影響entryIDを保存し、inventorySnapshotは再送時に現在所有者限定で再取得する。移管後の他所有者個体detailsを再送で返さない。

アカウント削除後も履歴として保持し、アカウント複製にはコピーしない。
