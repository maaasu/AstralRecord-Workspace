# dbo.user_setting 繝・・繝悶Ν險ｭ險・
繝ｦ繝ｼ繧ｶ蜊倅ｽ阪・險ｭ螳壼､繧剃ｿ晄戟縺吶ｋ繝・・繝悶Ν縲・
繧｢繧ｫ繧ｦ繝ｳ繝亥腰菴阪〒縺ｯ縺ｪ縺上～dbo.user` 縺ｮ繝励Ξ繧､繝､繝ｼ縺ｫ逶ｴ謗･邏舌▼縺代ｋ縲・
---

## 繝・・繝悶Ν諠・ｱ

| 鬆・岼 | 蛟､ |
|:--|:--|
| 繧ｹ繧ｭ繝ｼ繝槫錐 | `dbo` |
| 繝・・繝悶Ν蜷・| `user_setting` |
| 隲也炊蜷・| `dbo.user_setting` |
| 荳ｻ繧ｭ繝ｼ | `user_setting_id` |
| 螟夜Κ繧ｭ繝ｼ蜿ら・蜈・| `dbo.user.uuid` |

---

## 繧ｫ繝ｩ繝險ｭ險・
| 繧ｫ繝ｩ繝蜷・| 繝・・繧ｿ蝙・| PK | NotNull | 繝・ヵ繧ｩ繝ｫ繝亥宛邏・| 隱ｬ譏・|
|:--|:--|:-:|:-:|:-:|:--|
| `user_setting_id` | `UNIQUEIDENTIFIER` | 笳・| 笳・|  | 險ｭ螳壹Ξ繧ｳ繝ｼ繝・UUID |
| `user_id` | `UNIQUEIDENTIFIER` |  | 笳・|  | 險ｭ螳壼ｯｾ雎｡繝ｦ繝ｼ繧ｶ UUID縲Ａdbo.user.uuid` 繧貞盾辣ｧ縺吶ｋ |
| `setting_key` | `NVARCHAR(100)` |  | 笳・|  | 險ｭ螳壹く繝ｼ縲ゆｾ・ `ui.locale` |
| `setting_value_json` | `NVARCHAR(MAX)` |  | 笳・|  | 險ｭ螳壼､ JSON |
| `version` | `INT` |  | 笳・| `1` | 讌ｽ隕ｳ繝ｭ繝・け逕ｨ繝舌・繧ｸ繝ｧ繝ｳ |
| `created_at` | `DATETIME2(3)` |  | 笳・|  | 繝ｬ繧ｳ繝ｼ繝我ｽ懈・譌･譎・|
| `updated_at` | `DATETIME2(3)` |  | 笳・|  | 繝ｬ繧ｳ繝ｼ繝画峩譁ｰ譌･譎・|
| `created_by` | `UNIQUEIDENTIFIER` |  | 笳・|  | 菴懈・閠・UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | 笳・|  | 譖ｴ譁ｰ閠・UUID |
| `is_deleted` | `BIT` |  | 笳・| `0` | 隲也炊蜑企勁繝輔Λ繧ｰ |

---

## 蛻ｶ邏・・邏｢蠑戊ｨｭ險・
### 荳ｻ繧ｭ繝ｼ蛻ｶ邏・
| 蛻ｶ邏・錐 | 繧ｫ繝ｩ繝 | 遞ｮ蛻･ |
|:--|:--|:--|
| `PK_user_setting` | `user_setting_id` | PK |

### 螟夜Κ繧ｭ繝ｼ蛻ｶ邏・
| 蛻ｶ邏・錐 | 繧ｫ繝ｩ繝 | 蜿ら・蜈・| ON DELETE | ON UPDATE |
|:--|:--|:--|:--|:--|
| `FK_user_setting_user` | `user_id` | `dbo.user(uuid)` | NO ACTION | NO ACTION |

### CHECK 蛻ｶ邏・
| 蛻ｶ邏・錐 | 繧ｫ繝ｩ繝 | 譚｡莉ｶ | 隱ｬ譏・|
|:--|:--|:--|:--|
| `CK_user_setting_setting_key_not_blank` | `setting_key` | `LEN(LTRIM(RTRIM([setting_key]))) > 0` | 遨ｺ譁・ｭ励く繝ｼ遖∵ｭ｢ |
| `CK_user_setting_value_json` | `setting_value_json` | `ISJSON([setting_value_json]) = 1` | JSON 螯･蠖捺ｧ繝√ぉ繝・け |
| `CK_user_setting_version` | `version` | `[version] >= 1` | 繝舌・繧ｸ繝ｧ繝ｳ譛蟆丞､菫晁ｨｼ |

### 繝・ヵ繧ｩ繝ｫ繝亥宛邏・
| 蛻ｶ邏・錐 | 繧ｫ繝ｩ繝 | 蛟､ |
|:--|:--|:--|
| `DF_user_setting_version` | `version` | `1` |
| `DF_user_setting_is_deleted` | `is_deleted` | `0` |

---

## 繧､繝ｳ繝・ャ繧ｯ繧ｹ險ｭ險・
| 繧､繝ｳ繝・ャ繧ｯ繧ｹ蜷・| 繧ｫ繝ｩ繝 | 遞ｮ蛻･ | 逕ｨ騾・|
|:--|:--|:--|:--|
| `PK_user_setting` | `user_setting_id` | CLUSTERED | 荳ｻ繧ｭ繝ｼ讀懃ｴ｢ |
| `IX_user_setting_user_id` | `user_id` | NONCLUSTERED | 繝ｦ繝ｼ繧ｶ蜊倅ｽ崎ｨｭ螳夊ｪｭ縺ｿ蜿悶ｊ |
| `UX_user_setting_user_key_active` | `user_id`, `setting_key` | UNIQUE NONCLUSTERED | 譛牙柑險ｭ螳壹く繝ｼ驥崎､・亟豁｢ |
| `IX_user_setting_is_deleted` | `is_deleted` | NONCLUSTERED | 隲也炊蜑企勁繝輔ぅ繝ｫ繧ｿ繝ｪ繝ｳ繧ｰ |

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
