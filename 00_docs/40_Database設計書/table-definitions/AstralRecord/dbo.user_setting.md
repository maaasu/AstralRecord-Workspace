# dbo.user_setting チE�Eブル設訁E
ユーザ単位�E設定値を保持するチE�Eブル、E
アカウント単位ではなぁE`dbo.user`�E��Eレイヤー�E�に直接紐づける、E
---

## チE�Eブル惁E��

| 頁E��         | 値                 |
|:-----------|:-------------------|
| スキーマ名      | `dbo`              |
| チE�Eブル吁E     | `user_setting`     |
| 論理吁E        | `dbo.user_setting` |
| 主キー         | `user_setting_id`  |
| 外部キー参�E允E   | `dbo.user.uuid`    |

---

## カラム設訁E
| カラム吁E                | チE�Eタ垁E              | PK  | NotNull | チE��ォルト制紁E| 説昁E                                |
| :------------------- | :----------------- | :-: | :-----: | :-----: | :--------------------------------- |
| `user_setting_id`    | `UNIQUEIDENTIFIER` |  ◁E |    ◁E   |         | 設定レコーチEUUID                        |
| `user_id`            | `UNIQUEIDENTIFIER` |     |    ◁E   |         | 設定対象ユーザ UUID�E�EK ↁE`dbo.user.uuid`�E�E|
| `setting_key`        | `NVARCHAR(100)`    |     |    ◁E   |         | 設定キー�E�侁E `ui.locale`�E�E              |
| `setting_value_json` | `NVARCHAR(MAX)`    |     |    ◁E   |         | 設定値 JSON                           |
| `version`            | `INT`              |     |    ◁E   |   `1`   | 楽観ロチE��用バ�Eジョン                        |
| `created_at`         | `DATETIME2(3)`     |     |    ◁E   |         | レコード作�E日晁E                          |
| `updated_at`         | `DATETIME2(3)`     |     |    ◁E   |         | レコード更新日晁E                          |
| `created_by`         | `UNIQUEIDENTIFIER` |     |    ◁E   |         | 作�E老EUUID                           |
| `updated_by`         | `UNIQUEIDENTIFIER` |     |    ◁E   |         | 更新老EUUID                           |
| `is_deleted`         | `BIT`              |     |    ◁E   |   `0`   | 論理削除フラグ                            |

---

## 制紁E�E索引設訁E
### 主キー制紁E| 制紁E��               | カラム              | 種別 |
|:------------------|:-----------------|:---|
| `PK_user_setting` | `user_setting_id` | PK |

### 外部キー制紁E| 制紁E��                        | カラム      | 参�E允E            | ON DELETE | ON UPDATE |
|:---------------------------|:---------|:-----------------|:----------|:----------|
| `FK_user_setting_user`     | `user_id`| `dbo.user(uuid)` | NO ACTION | NO ACTION |

### CHECK 制紁E| 制紁E��                                  | カラム                | 条件                                   | 説昁E|
|:-------------------------------------|:-------------------|:-------------------------------------|:-----|
| `CK_user_setting_setting_key_not_blank` | `setting_key`      | `LEN(LTRIM(RTRIM([setting_key]))) > 0` | 空斁E��キー禁止 |
| `CK_user_setting_value_json`         | `setting_value_json` | `ISJSON([setting_value_json]) = 1`     | JSON 妥当性チェチE�� |
| `CK_user_setting_version`            | `version`          | `[version] >= 1`                        | バ�Eジョン最小値保証 |

### チE��ォルト制紁E| 制紁E��                              | カラム         | 値   |
|:---------------------------------|:------------|:----|
| `DF_user_setting_version`        | `version`   | `1` |
| `DF_user_setting_is_deleted`     | `is_deleted`| `0` |

---

## インチE��クス設訁E
| インチE��クス吁E                         | カラム                      | 種別             | 用送E|
|:-----------------------------------|:-------------------------|:---------------|:-----|
| `PK_user_setting`                  | `user_setting_id`        | CLUSTERED       | 主キー検索 |
| `IX_user_setting_user_id`          | `user_id`                | NONCLUSTERED    | ユーザ単位�E設定読叁E|
| `UX_user_setting_user_key_active`  | `user_id`, `setting_key` | UNIQUE NONCLUSTERED�E�Eilter�E�E| 有効設定キー重褁E��止 |
| `IX_user_setting_is_deleted`       | `is_deleted`             | NONCLUSTERED    | 論理削除フィルタリング |

---

## DDL

```sql
CREATE TABLE [dbo].[user_setting] (
    [user_setting_id]    UNIQUEIDENTIFIER  NOT NULL,
    [user_id]            UNIQUEIDENTIFIER  NOT NULL,
    [setting_key]        NVARCHAR(100)     NOT NULL,
    [setting_value_json] NVARCHAR(MAX)     NOT NULL,
    [version]            INT               NOT NULL  CONSTRAINT [DF_user_setting_version]    DEFAULT (1),
    [created_at]         DATETIME2(3)      NOT NULL,
    [updated_at]         DATETIME2(3)      NOT NULL,
    [created_by]         UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]         UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]         BIT               NOT NULL  CONSTRAINT [DF_user_setting_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_user_setting] PRIMARY KEY CLUSTERED ([user_setting_id]),
    CONSTRAINT [FK_user_setting_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_user_setting_setting_key_not_blank] CHECK (LEN(LTRIM(RTRIM([setting_key]))) > 0),
    CONSTRAINT [CK_user_setting_value_json] CHECK (ISJSON([setting_value_json]) = 1),
    CONSTRAINT [CK_user_setting_version] CHECK ([version] >= 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_user_setting_user_id]
    ON [dbo].[user_setting] ([user_id]);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_user_setting_user_key_active]
    ON [dbo].[user_setting] ([user_id], [setting_key])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_user_setting_is_deleted]
    ON [dbo].[user_setting] ([is_deleted]);
GO
```
