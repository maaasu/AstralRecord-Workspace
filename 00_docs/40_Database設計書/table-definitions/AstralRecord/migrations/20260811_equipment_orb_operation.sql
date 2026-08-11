SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[equipment_orb_operation]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[equipment_orb_operation] (
        [operation_id]                       UNIQUEIDENTIFIER  NOT NULL,
        [account_id]                         UNIQUEIDENTIFIER  NOT NULL,
        [equipment_instance_id]              UNIQUEIDENTIFIER  NOT NULL,
        [orb_inventory_entry_id]             UNIQUEIDENTIFIER  NOT NULL,
        [orb_item_id]                        NVARCHAR(128)     NOT NULL,
        [operation_type]                     NVARCHAR(32)      NOT NULL,
        [request_hash]                       CHAR(64)          NOT NULL,
        [result_code]                        NVARCHAR(32)      NOT NULL,
        [result_payload_json]                NVARCHAR(MAX)     NOT NULL,
        [payment_consumed]                   BIT               NOT NULL,
        [affected_inventory_entry_ids_json]  NVARCHAR(MAX)     NOT NULL,
        [created_at]                         DATETIME2(3)       NOT NULL,
        [completed_at]                       DATETIME2(3)       NOT NULL,
        [created_by]                         UNIQUEIDENTIFIER   NOT NULL,
        CONSTRAINT [PK_equipment_orb_operation] PRIMARY KEY CLUSTERED ([operation_id]),
        CONSTRAINT [CK_equipment_orb_operation_result_payload_json]
            CHECK (ISJSON([result_payload_json]) = 1),
        CONSTRAINT [CK_equipment_orb_operation_affected_entries_json]
            CHECK (ISJSON([affected_inventory_entry_ids_json]) = 1)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_equipment_orb_operation_account_created_at'
      AND [object_id] = OBJECT_ID(N'[dbo].[equipment_orb_operation]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_equipment_orb_operation_account_created_at]
        ON [dbo].[equipment_orb_operation] ([account_id], [created_at]);
END;

-- enhancement_material から orb へのマスタ種別変更後も、既存所持 entry をそのまま利用可能にする。
-- item_id と個数は維持し、楽観的整合性確認のため updated_at だけを更新する。
UPDATE [dbo].[inventory_entry]
SET [item_category] = N'orb',
    [updated_at] = SYSUTCDATETIME()
WHERE LOWER(LTRIM(RTRIM([item_category]))) = N'enhancement_material';

-- 旧カテゴリの出品から購入後に enhancement_material entry が再生成されないよう、
-- 市場の現行出品と集計履歴も同じ category へ移行する。
IF OBJECT_ID(N'[dbo].[market_listing]', N'U') IS NOT NULL
BEGIN
    UPDATE [dbo].[market_listing]
    SET [item_category] = N'orb',
        [valuation_signature] = CASE
            WHEN LOWER(LEFT([valuation_signature], LEN(N'enhancement_material|'))) = N'enhancement_material|'
                THEN STUFF([valuation_signature], 1, LEN(N'enhancement_material|'), N'orb|')
            ELSE [valuation_signature]
        END,
        [valuation_snapshot_json] = CASE
            WHEN [valuation_snapshot_json] IS NOT NULL AND ISJSON([valuation_snapshot_json]) = 1 THEN
                JSON_MODIFY(
                    JSON_MODIFY([valuation_snapshot_json], N'$.ItemCategory', N'orb'),
                    N'$.ValuationSignature',
                    CASE
                        WHEN LOWER(LEFT(
                            COALESCE([valuation_signature], JSON_VALUE([valuation_snapshot_json], N'$.ValuationSignature')),
                            LEN(N'enhancement_material|')
                        )) = N'enhancement_material|'
                            THEN STUFF(
                                COALESCE([valuation_signature], JSON_VALUE([valuation_snapshot_json], N'$.ValuationSignature')),
                                1,
                                LEN(N'enhancement_material|'),
                                N'orb|'
                            )
                        ELSE COALESCE(
                            [valuation_signature],
                            JSON_VALUE([valuation_snapshot_json], N'$.ValuationSignature')
                        )
                    END
                )
            ELSE [valuation_snapshot_json]
        END,
        [version] = [version] + 1,
        [updated_at] = SYSUTCDATETIME()
    WHERE LOWER(LTRIM(RTRIM([item_category]))) = N'enhancement_material';
END;

IF OBJECT_ID(N'[dbo].[market_transaction]', N'U') IS NOT NULL
BEGIN
    UPDATE [dbo].[market_transaction]
    SET [item_category] = N'orb',
        [valuation_signature] = CASE
            WHEN LOWER(LEFT([valuation_signature], LEN(N'enhancement_material|'))) = N'enhancement_material|'
                THEN STUFF([valuation_signature], 1, LEN(N'enhancement_material|'), N'orb|')
            ELSE [valuation_signature]
        END,
        [valuation_snapshot_json] = CASE
            WHEN [valuation_snapshot_json] IS NOT NULL AND ISJSON([valuation_snapshot_json]) = 1 THEN
                JSON_MODIFY(
                    JSON_MODIFY([valuation_snapshot_json], N'$.ItemCategory', N'orb'),
                    N'$.ValuationSignature',
                    CASE
                        WHEN LOWER(LEFT(
                            COALESCE([valuation_signature], JSON_VALUE([valuation_snapshot_json], N'$.ValuationSignature')),
                            LEN(N'enhancement_material|')
                        )) = N'enhancement_material|'
                            THEN STUFF(
                                COALESCE([valuation_signature], JSON_VALUE([valuation_snapshot_json], N'$.ValuationSignature')),
                                1,
                                LEN(N'enhancement_material|'),
                                N'orb|'
                            )
                        ELSE COALESCE(
                            [valuation_signature],
                            JSON_VALUE([valuation_snapshot_json], N'$.ValuationSignature')
                        )
                    END
                )
            ELSE [valuation_snapshot_json]
        END
    WHERE LOWER(LTRIM(RTRIM([item_category]))) = N'enhancement_material';
END;

IF OBJECT_ID(N'[dbo].[market_price_snapshot]', N'U') IS NOT NULL
BEGIN
    UPDATE [dbo].[market_price_snapshot]
    SET [item_category] = N'orb',
        [valuation_signature] = CASE
            WHEN LOWER(LEFT([valuation_signature], LEN(N'enhancement_material|'))) = N'enhancement_material|'
                THEN STUFF([valuation_signature], 1, LEN(N'enhancement_material|'), N'orb|')
            ELSE [valuation_signature]
        END
    WHERE LOWER(LTRIM(RTRIM([item_category]))) = N'enhancement_material';
END;

COMMIT TRANSACTION;
GO
