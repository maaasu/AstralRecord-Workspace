# dbo.account テーブル定義

プレイヤー（`dbo.user`）が複数所持できるゲーム内アカウント（キャラクター）管理テーブル。  
各アカウントはプレイヤー UUID に紐付き、キャラクター名・スロット番号・現在の選択状態・権限モード・プレイヤーレベル進行を管理します。

---

## テーブル情報

| 項目         | 値                  |
|:-----------|:-------------------|
| スキーマ名      | `dbo`              |
| テーブル名      | `account`          |
| 完全修飾名      | `dbo.account`      |
| 主キー        | `uuid`             |
| 外部キー参照先    | `dbo.user.uuid`    |

---

## カラム定義

| カラム名           | データ型               | PK | NotNull | デフォルト値 | 説明                                                      |
|:---------------|:-------------------|:--:|:-------:|:------:|:--------------------------------------------------------|
| `uuid`         | `UNIQUEIDENTIFIER` | ○  |    ○    |        | アカウント固有の UUID                                           |
| `user_id`      | `UNIQUEIDENTIFIER` |    |    ○    |        | 所有プレイヤーの UUID（FK → `dbo.user.uuid`）                     |
| `account_name` | `NVARCHAR(50)`     |    |    ○    |        | アカウント名（キャラクター名）                                         |
| `slot_index`   | `INT`              |    |    ○    |        | アカウントスロット番号（0 始まり）                                      |
| `is_active`    | `BIT`              |    |    ○    |  `0`   | 現在選択中フラグ（`1`: 選択中 / `0`: 未選択）                           |
| `mode`         | `TINYINT`          |    |    ○    |  `0`   | 権限モード（`0`: プレイヤー / `1`: ビルダー / `2`: 管理者）                |
| `menu_shortcuts_json` | `NVARCHAR(MAX)` |    |    ○    | `["INVENTORY_NORMAL","INVENTORY_EQUIPMENT","INVENTORY_RUNE","INVENTORY_CURRENCY"]` | 2x2 craft shortcut settings JSON array |
| `level`        | `INT`              |    |    ○    |  `1`   | プレイヤーレベル。初期値は `1`、最小値も `1`                             |
| `total_experience` | `BIGINT`       |    |    ○    |  `0`   | 累計経験値。加算専用で負数不可                                           |
| `created_at`   | `DATETIME2(3)`     |    |    ○    |        | レコード作成日時                                                |
| `updated_at`   | `DATETIME2(3)`     |    |    ○    |        | レコード最終更新日時                                              |
| `created_by`   | `UNIQUEIDENTIFIER` |    |    ○    |        | 作成者の UUID                                               |
| `updated_by`   | `UNIQUEIDENTIFIER` |    |    ○    |        | 最終更新者の UUID                                             |
| `is_deleted`   | `BIT`              |    |    ○    |  `0`   | 論理削除フラグ（`1`: 削除済 / `0`: 未削除）                            |

### mode 値定義

| 値   | 識別名    | 説明                      |
|:----|:-------|:------------------------|
| `0` | プレイヤー  | 通常プレイヤー（デフォルト）          |
| `1` | ビルダー   | ビルド権限を持つプレイヤー           |
| `2` | 管理者    | サーバー管理権限を持つプレイヤー        |

---

## 制約定義

### 主キー制約

| 制約名          | カラム    | 種別 |
|:-------------|:-------|:---|
| `PK_account` | `uuid` | PK |

### 外部キー制約

| 制約名                   | カラム       | 参照先              | ON DELETE | ON UPDATE |
|:----------------------|:----------|:-----------------|:----------|:----------|
| `FK_account_user`     | `user_id` | `dbo.user(uuid)` | NO ACTION | NO ACTION |

### UNIQUE 制約

| 制約名                        | カラム                        | 説明                                 |
|:---------------------------|:---------------------------|:-----------------------------------|
| `UQ_account_user_slot`     | `user_id`, `slot_index`    | 同一プレイヤーのスロット番号の重複を防ぐ               |

### CHECK 制約

| 制約名                  | カラム    | 条件                | 説明                     |
|:---------------------|:-------|:------------------|:-----------------------|
| `CK_account_mode`    | `mode` | `IN (0, 1, 2)`    | 権限モードの有効値を制限する         |
| `CK_account_menu_shortcuts_json` | `menu_shortcuts_json` | `ISJSON(menu_shortcuts_json) = 1` | shortcut settings JSON validation |
| `CK_account_level` | `level` | `>= 1` | レベルの下限を制限する |
| `CK_account_total_experience` | `total_experience` | `>= 0` | 経験値の負数保存を防ぐ |

### デフォルト制約

| 制約名                              | カラム                   | 値                                                                                  |
| :------------------------------- | :-------------------- | :--------------------------------------------------------------------------------- |
| `DF_account_mode`                | `mode`                | `0`                                                                                |
| `DF_account_is_active`           | `is_active`           | `0`                                                                                |
| `DF_account_is_deleted`          | `is_deleted`          | `0`                                                                                |
| `DF_account_menu_shortcuts_json` | `menu_shortcuts_json` | `["INVENTORY_NORMAL","INVENTORY_EQUIPMENT","INVENTORY_RUNE","INVENTORY_CURRENCY"]` |
| `DF_account_level`               | `level`               | `1`                                                                                |
| `DF_account_total_experience`    | `total_experience`    | `0`                                                                                |

---

## インデックス定義

| インデックス名                    | カラム          | 種別             | 用途                    |
|:---------------------------|:-------------|:---------------|:----------------------|
| `PK_account`               | `uuid`       | CLUSTERED（主キー） | 主キー検索                 |
| `IX_account_user_id`       | `user_id`    | NONCLUSTERED   | プレイヤー所有アカウント一覧取得      |
| `IX_account_is_deleted`    | `is_deleted` | NONCLUSTERED   | 論理削除フィルタリング           |

---

## DDL

```sql
CREATE TABLE [dbo].[account] (
    [uuid]           UNIQUEIDENTIFIER  NOT NULL,
    [user_id]        UNIQUEIDENTIFIER  NOT NULL,
    [account_name]   NVARCHAR(50)      NOT NULL,
    [slot_index]     INT               NOT NULL,
    [is_active]      BIT               NOT NULL  CONSTRAINT [DF_account_is_active]   DEFAULT (0),
    [mode]           TINYINT           NOT NULL  CONSTRAINT [DF_account_mode]         DEFAULT (0),
    [menu_shortcuts_json] NVARCHAR(MAX) NOT NULL  CONSTRAINT [DF_account_menu_shortcuts_json] DEFAULT (N'["INVENTORY_NORMAL","INVENTORY_EQUIPMENT","INVENTORY_RUNE","INVENTORY_CURRENCY"]'),
    [level]          INT               NOT NULL  CONSTRAINT [DF_account_level]        DEFAULT (1),
    [total_experience] BIGINT          NOT NULL  CONSTRAINT [DF_account_total_experience] DEFAULT (0),
    [created_at]     DATETIME2(3)      NOT NULL,
    [updated_at]     DATETIME2(3)      NOT NULL,
    [created_by]     UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]     UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]     BIT               NOT NULL  CONSTRAINT [DF_account_is_deleted]  DEFAULT (0),

    CONSTRAINT [PK_account] PRIMARY KEY CLUSTERED ([uuid]),
    CONSTRAINT [FK_account_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_account_user_slot] UNIQUE ([user_id], [slot_index]),
    CONSTRAINT [CK_account_mode] CHECK ([mode] IN (0, 1, 2)),
    CONSTRAINT [CK_account_menu_shortcuts_json] CHECK (ISJSON([menu_shortcuts_json]) = 1),
    CONSTRAINT [CK_account_level] CHECK ([level] >= 1),
    CONSTRAINT [CK_account_total_experience] CHECK ([total_experience] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_account_user_id]
    ON [dbo].[account] ([user_id]);
GO

CREATE NONCLUSTERED INDEX [IX_account_is_deleted]
    ON [dbo].[account] ([is_deleted]);
GO
```

---

## 用途

| 用途           | 説明                                                |
|:-------------|:--------------------------------------------------|
| キャラクター管理     | 1 人のプレイヤーが複数のゲーム内キャラクター（アカウント）を所持・切り替えできる仕組みを提供する |
| スロット番号管理     | `slot_index` により、キャラクター選択画面でのスロット位置を一意に管理する       |
| アクティブアカウント管理 | `is_active` フラグにより、プレイヤーが現在使用中のアカウントを識別する         |
| 権限モード管理      | `mode` により、アカウントの権限レベル（管理者、プレイヤー、ビルダー）を管理する       |
| プレイヤーレベル管理  | `level` と `total_experience` により、アカウント単位の進行度を永続化する     |
| 論理削除         | `is_deleted` フラグにより、キャラクターの削除を物理削除せず論理削除として管理する   |

---

## ソースコード参照

| 種別         | パス                                                                                            |
|:-----------|:----------------------------------------------------------------------------------------------|
| Repository | `src/main/java/io/github/maaasu/astralRecord/feature/account/repository/AccountRepository.kt` |
| Table      | `src/main/java/io/github/maaasu/astralRecord/feature/account/repository/AccountTable.kt`      |
