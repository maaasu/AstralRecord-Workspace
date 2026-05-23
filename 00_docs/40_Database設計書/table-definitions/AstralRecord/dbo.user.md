# dbo.user テーブル定義

プレイヤーごとの基本情報管理テーブル。  
参加履歴・BAN管理・IP制限・選択中アカウントの紐付けを行います。

---

## テーブル情報

| 項目         | 値          |
|:-----------|:-----------|
| スキーマ名      | `dbo`      |
| テーブル名      | `user`     |
| 完全修飾名      | `dbo.user` |
| 主キー        | `uuid`     |
| 外部キー参照先    | なし         |

---

## カラム定義

| カラム名             | データ型               | PK | NotNull | デフォルト値 | 説明                                                                           |
|:-----------------|:-------------------|:--:|:-------:|:------:|:-----------------------------------------------------------------------------|
| `uuid`           | `UNIQUEIDENTIFIER` | ○  |    ○    |        | プレイヤー固有の UUID（Minecraft UUID）                                                |
| `mcid`           | `NVARCHAR(20)`     |    |    ○    |        | プレイヤーの Minecraft ID                                                          |
| `join_date`      | `DATETIME2(3)`     |    |    ○    |        | 初回参加日時                                                                       |
| `last_join_date` | `DATETIME2(3)`     |    |    ○    |        | 最終参加日時                                                                       |
| `global_ip`      | `NVARCHAR(45)`     |    |    ○    |        | プレイヤーのグローバル IP（IPv6 対応）                                                      |
| `account_id`     | `UNIQUEIDENTIFIER` |    |         |        | 現在選択中のアカウント UUID（参照先: `dbo.account.uuid`）。初回 INSERT 時は `NULL`、account 作成後に UPDATE される |
| `ban_indefinite` | `BIT`              |    |    ○    |  `0`   | 無期限 BAN フラグ（`1`: 有効 / `0`: 無効）                                               |
| `ban_date`       | `DATETIME2(0)`     |    |         |        | 期限付き BAN の終了日時。現在日時より未来の場合に kick                                             |
| `kick_ip`        | `BIT`              |    |    ○    |  `1`   | 同一 IP プレイヤーの参加拒否フラグ（`1`: 有効 / `0`: 無効）                                       |
| `permission`     | `INT`              |    |    ○    |  `0`   | 権限レベル（`0`: 一般 / 数値が大きいほど高権限）                                                 |
| `created_at`     | `DATETIME2(3)`     |    |    ○    |        | レコード作成日時                                                                     |
| `updated_at`     | `DATETIME2(3)`     |    |    ○    |        | レコード最終更新日時                                                                   |
| `created_by`     | `UNIQUEIDENTIFIER` |    |    ○    |        | 作成者の UUID                                                                    |
| `updated_by`     | `UNIQUEIDENTIFIER` |    |    ○    |        | 最終更新者の UUID                                                                  |
| `is_deleted`     | `BIT`              |    |    ○    |  `0`   | 論理削除フラグ（`1`: 削除済 / `0`: 未削除）                                                 |

---

## 制約定義

### 主キー制約

| 制約名       | カラム    | 種別 |
|:----------|:-------|:---|
| `PK_user` | `uuid` | PK |

### 外部キー制約

なし。

> **設計備考:**  
> `dbo.user` と `dbo.account` は互いに参照し合う循環依存関係にあります。  
> SQL Server は遅延 FK 評価をサポートしていないため、`dbo.user.account_id` への `FK_user_account` 制約は設けず、  
> アプリケーション層（`UserService.registerNewUser`）でデータ整合性を保証します。  
> 登録フロー: `dbo.user` INSERT（`account_id = NULL`）→ `dbo.account` INSERT → `dbo.user.account_id` UPDATE

### デフォルト制約

| 制約名                      | カラム              | 値   |
|:-------------------------|:-----------------|:----|
| `DF_user_ban_indefinite` | `ban_indefinite` | `0` |
| `DF_user_kick_ip`        | `kick_ip`        | `1` |
| `DF_user_permission`     | `permission`     | `0` |
| `DF_user_is_deleted`     | `is_deleted`     | `0` |

---

## インデックス定義

| インデックス名              | カラム          | 種別             | 用途             |
|:---------------------|:-------------|:---------------|:---------------|
| `PK_user`            | `uuid`       | CLUSTERED（主キー） | 主キー検索          |
| `IX_user_mcid`       | `mcid`       | NONCLUSTERED   | MCID 検索（ログイン時） |
| `IX_user_global_ip`  | `global_ip`  | NONCLUSTERED   | IP 制限チェック（参加時） |
| `IX_user_account_id` | `account_id` | NONCLUSTERED   | アカウント紐付け検索     |

---

## DDL

```sql
-- STEP 1: dbo.user（account_id は NULL 許容で作成）
CREATE TABLE [dbo].[user] (
    [uuid]             UNIQUEIDENTIFIER  NOT NULL,
    [mcid]             NVARCHAR(20)      NOT NULL,
    [join_date]        DATETIME2(3)      NOT NULL,
    [last_join_date]   DATETIME2(3)      NOT NULL,
    [global_ip]        NVARCHAR(45)      NOT NULL,
    [account_id]       UNIQUEIDENTIFIER      NULL,  -- 初回 INSERT 時 NULL、account 作成後に UPDATE
    [ban_indefinite]   BIT               NOT NULL  CONSTRAINT [DF_user_ban_indefinite]  DEFAULT (0),
    [ban_date]         DATETIME2(0)          NULL,
    [kick_ip]          BIT               NOT NULL  CONSTRAINT [DF_user_kick_ip]          DEFAULT (1),
    [permission]       INT               NOT NULL  CONSTRAINT [DF_user_permission]        DEFAULT (0),
    [created_at]       DATETIME2(3)      NOT NULL,
    [updated_at]       DATETIME2(3)      NOT NULL,
    [created_by]       UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]       UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]       BIT               NOT NULL  CONSTRAINT [DF_user_is_deleted]       DEFAULT (0),

    CONSTRAINT [PK_user] PRIMARY KEY CLUSTERED ([uuid])
);
GO

CREATE NONCLUSTERED INDEX [IX_user_uuid]
    ON [dbo].[user] ([uuid]);
GO

CREATE NONCLUSTERED INDEX [IX_user_global_ip]
    ON [dbo].[user] ([global_ip]);
GO

-- STEP 3: dbo.account 作成後にインデックスを追加
CREATE NONCLUSTERED INDEX [IX_user_account_id]
    ON [dbo].[user] ([account_id]);
GO
```

---

## 用途

| 用途           | 説明                                                                             |
|:-------------|:-------------------------------------------------------------------------------|
| 基本情報管理       | プレイヤーの UUID・MCID・参加日時・グローバル IP を保持する                                           |
| 参加履歴の追跡      | `join_date`（初回）と `last_join_date`（最終）で参加状況を管理する                                |
| 無期限 BAN 管理   | `ban_indefinite = 1` のプレイヤーをサーバー接続時に kick する                                   |
| 期限付き BAN 管理  | `ban_date` が現在日時より未来の場合に kick する（`NULL` は無効）                                   |
| IP 制限による参加制御 | `kick_ip = 1` のプレイヤーと同一 IP の接続を拒否する                                            |
| 権限管理         | `permission` でプレイヤーの権限レベルを管理する（`0`: 一般、数値が大きいほど高権限）                            |
| 選択中アカウント管理   | `account_id` で選択中アカウントを管理する。`NULL` の場合は `dbo.account.is_active = 1` のレコードを参照する |
| 論理削除         | `is_deleted = 1` で物理削除を行わず、データを保持したまま無効化する                                     |

---

## ソースコード参照

| 種別         | パス                                                                                      |
|:-----------|:----------------------------------------------------------------------------------------|
| Repository | `src/main/java/io/github/maaasu/astralRecord/feature/user/repository/UserRepository.kt` |
| Table      | `src/main/java/io/github/maaasu/astralRecord/feature/user/repository/UserTable.kt`      |
