# dbo.account_benefits

アカウント専用の有償特典正本。VIPはユーザー権限・account.modeと独立し、選択中アカウントだけをネットワークアクセス判定に使う。

| 列 | 型 | 意味 |
|---|---|---|
| account_id | uniqueidentifier PK/FK | account.uuid |
| instance_priority_uses | int >= 0 | インスタンス優先予約残高 |
| doner_expires_at | datetime2(3) nullable | 待機中ASTRALDER日数を含むDONER終了UTC |
| astralder_expires_at | datetime2(3) nullable | ASTRALDER終了UTC |
| astralder_daily_credits_remaining | int >= 0 | 現在継続中ASTRALDERの未付与日次上限 |
| last_daily_claim_date | date nullable | 最後に付与したJST日付 |

ASTRALDERを先に消化する。DONERを追加するとmax(現在時刻, ASTRALDER期限, DONER期限)+日数となる。ASTRALDER追加時はmax(現在時刻, ASTRALDER期限)+日数を終了とし、残るDONER期限も同日数延長する。期限は排他的。日次付与は有効期間のログイン日にJST一日一回だけ1回、未ログイン日の遡及なし。ASTRALDER1日追加につき付与上限も1増加（20日券は20）。失効後の再開では以前の未付与枠を破棄する。

アカウント削除時は同transactionでこの行を物理削除する。アカウント複製・最後のアカウント削除後の再作成には移さない。

## DDL

```sql
CREATE TABLE [dbo].[account_benefits] (
    [account_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_account_benefits] PRIMARY KEY,
    [instance_priority_uses] INT NOT NULL CONSTRAINT [DF_account_benefits_uses] DEFAULT (0),
    [doner_expires_at] DATETIME2(3) NULL,
    [astralder_expires_at] DATETIME2(3) NULL,
    [astralder_daily_credits_remaining] INT NOT NULL CONSTRAINT [DF_account_benefits_daily] DEFAULT (0),
    [last_daily_claim_date] DATE NULL,
    CONSTRAINT [FK_account_benefits_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]),
    CONSTRAINT [CK_account_benefits_uses] CHECK ([instance_priority_uses] >= 0),
    CONSTRAINT [CK_account_benefits_daily] CHECK ([astralder_daily_credits_remaining] >= 0)
);
```
