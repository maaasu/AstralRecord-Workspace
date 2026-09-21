IF COL_LENGTH(N'dbo.skilltree_operation', N'changes_json') IS NULL
    ALTER TABLE [dbo].[skilltree_operation] ADD [changes_json] NVARCHAR(MAX) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_skilltree_operation_changes_json')
    ALTER TABLE [dbo].[skilltree_operation] ADD CONSTRAINT [CK_skilltree_operation_changes_json]
    CHECK ([changes_json] IS NULL OR ISJSON([changes_json]) = 1);
GO
ALTER TABLE [dbo].[skilltree_operation] DROP CONSTRAINT [CK_skilltree_operation_action];
ALTER TABLE [dbo].[skilltree_operation] ADD CONSTRAINT [CK_skilltree_operation_action]
CHECK (([action] IN ('UNLOCK','RELOCK') AND [changes_json] IS NULL) OR ([action] = 'BATCH' AND [node_id] = 'batch' AND [changes_json] IS NOT NULL));
GO
