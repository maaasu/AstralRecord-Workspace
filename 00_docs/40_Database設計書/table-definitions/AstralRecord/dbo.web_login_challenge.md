# dbo.web_login_challenge 郢昴・繝ｻ郢晄じﾎ晁楜螟ゑｽｾ・ｩ

WEB 郢晏干ﾎ樒ｹｧ・､郢晢ｽ､郢晢ｽｼ郢晢ｽｭ郢ｧ・ｰ郢ｧ・､郢晢ｽｳ騾包ｽｨ邵ｺ・ｮ驕擾ｽｭ陷ｻ・ｽ郢晢ｽｻ闕ｳﾂ陜玲ｨ｣蜑樒ｹｧ鄙ｫﾎ溽ｹｧ・ｰ郢ｧ・､郢晢ｽｳ郢昶・ﾎ慕ｹ晢ｽｬ郢晢ｽｳ郢ｧ・ｸ郢ｧ蝣､・ｮ・｡騾・・笘・ｹｧ荵昴Θ郢晢ｽｼ郢晄じﾎ晉ｸｲ繝ｻ
Plugin 邵ｺ繝ｻ`/web login` 邵ｺ・ｫ郢ｧ蛹ｻ・企具ｽｺ髯ｦ迹夲ｽｦ竏ｵ・ｱ繧・ｼ邵ｺ貅佩溽ｹｧ・ｰ郢ｧ・､郢晢ｽｳ郢ｧ・ｳ郢晢ｽｼ郢晏ｳｨ・堤ｹ昜ｸ翫Ε郢ｧ・ｷ郢晢ｽ･陋ｹ謔ｶ・邵ｺ・ｦ闖ｫ譎擾ｽｭ蛟･・邵ｲ莉戲B 邵ｺ謔溘・陷牙ｸ呻ｼ邵ｺ貅倥＆郢晢ｽｼ郢晏ｳｨ・・API 驍ｨ讙守ｽｰ邵ｺ・ｧ隶諛・ｽｨ・ｼ郢晢ｽｻ雎ｸ驛・ｽｲ・ｻ邵ｺ蜷ｶ・狗ｸｺ貅假ｽ∫ｸｺ・ｫ闖ｴ・ｿ騾包ｽｨ邵ｺ蜷ｶ・狗ｸｲ繝ｻ
---

## 郢昴・繝ｻ郢晄じﾎ晁ｫ繝ｻ・ｰ・ｱ

| 鬯・・蟯ｼ | 陋滂ｽ､ |
|:--|:--|
| 郢ｧ・ｹ郢ｧ・ｭ郢晢ｽｼ郢晄ｧｫ骭・| `dbo` |
| 郢昴・繝ｻ郢晄じﾎ晁惺繝ｻ| `web_login_challenge` |
| 陞ｳ謔溘・闖ｫ・ｮ鬯滂ｽｾ陷ｷ繝ｻ| `dbo.web_login_challenge` |
| 闕ｳ・ｻ郢ｧ・ｭ郢晢ｽｼ | `challenge_id` |
| 陞溷､慚夂ｹｧ・ｭ郢晢ｽｼ陷ｿ繧峨・陷医・| `dbo.user.uuid` |

---

## 郢ｧ・ｫ郢晢ｽｩ郢晢｣ｰ陞ｳ螟ゑｽｾ・ｩ

| 郢ｧ・ｫ郢晢ｽｩ郢晢｣ｰ陷ｷ繝ｻ| 郢昴・繝ｻ郢ｧ・ｿ陜吶・| PK | NotNull | 郢昴・繝ｵ郢ｧ・ｩ郢晢ｽｫ郢昜ｺ･ﾂ・､ | 髫ｱ・ｬ隴上・|
|:--|:--|:--:|:--:|:--|:--|
| `challenge_id` | `UNIQUEIDENTIFIER` | 隨ｳ繝ｻ| 隨ｳ繝ｻ|  | 郢晢ｽｭ郢ｧ・ｰ郢ｧ・､郢晢ｽｳ郢昶・ﾎ慕ｹ晢ｽｬ郢晢ｽｳ郢ｧ・ｸ ID |
| `user_id` | `UNIQUEIDENTIFIER` |  | 隨ｳ繝ｻ|  | 陝・ｽｾ髮趣ｽ｡郢晏干ﾎ樒ｹｧ・､郢晢ｽ､郢晢ｽｼ UUID繝ｻ繝ｻK 遶翫・`dbo.user.uuid`繝ｻ繝ｻ|
| `login_code_hash` | `NVARCHAR(256)` |  | 隨ｳ繝ｻ|  | 髯ｦ・ｨ驕会ｽｺ騾包ｽｨ郢ｧ・ｳ郢晢ｽｼ郢晏ｳｨ繝ｻ郢昜ｸ翫Ε郢ｧ・ｷ郢晢ｽ･邵ｲ繧・ｽｹ・ｳ隴√・縺慕ｹ晢ｽｼ郢晏ｳｨ繝ｻ闖ｫ譎擾ｽｭ蛟･・邵ｺ・ｪ邵ｺ繝ｻ|
| `issued_at` | `DATETIME2(3)` |  | 隨ｳ繝ｻ|  | 騾具ｽｺ髯ｦ譴ｧ蠕玖ｭ弱・|
| `expires_at` | `DATETIME2(3)` |  | 隨ｳ繝ｻ|  | 隴帷甥譟題ｭ帶ｻ・応 |
| `consumed_at` | `DATETIME2(3)` |  |  |  | 雎ｸ驛・ｽｲ・ｻ隴鯉ｽ･隴弱ｅﾂ繝ｻULL 邵ｺ・ｮ陜｣・ｴ陷ｷ蛹ｻ繝ｻ隴幢ｽｪ闖ｴ・ｿ騾包ｽｨ |
| `revoked_at` | `DATETIME2(3)` |  |  |  | 陞滂ｽｱ陷会ｽｹ隴鯉ｽ･隴弱ｅﾂ繧・・騾具ｽｺ髯ｦ蠕鯉ｽ・ｩ慕距逡題棔・ｱ陷会ｽｹ隴弱ｅ竊馴坎・ｭ陞ｳ螢ｹ笘・ｹｧ繝ｻ|
| `failed_attempts` | `INT` |  | 隨ｳ繝ｻ| `0` | 陷茨ｽ･陷牙ｸ幢ｽ､・ｱ隰ｨ諤懷ｱ楢ｬｨ・ｰ |
| `issued_by_server` | `NVARCHAR(50)` |  | 隨ｳ繝ｻ|  | 騾具ｽｺ髯ｦ謔溘・郢ｧ・ｵ郢晢ｽｼ郢晁・繝ｻ髫ｴ莨懈肩陝・・|
| `created_at` | `DATETIME2(3)` |  | 隨ｳ繝ｻ|  | 郢晢ｽｬ郢ｧ・ｳ郢晢ｽｼ郢晄・・ｽ諛医・隴鯉ｽ･隴弱・|

---

## 陋ｻ・ｶ驍上・・ｮ螟ゑｽｾ・ｩ

### 闕ｳ・ｻ郢ｧ・ｭ郢晢ｽｼ陋ｻ・ｶ驍上・
| 陋ｻ・ｶ驍上・骭・| 郢ｧ・ｫ郢晢ｽｩ郢晢｣ｰ | 驕橸ｽｮ陋ｻ・･ |
|:--|:--|:--|
| `PK_web_login_challenge` | `challenge_id` | PK |

### 陞溷､慚夂ｹｧ・ｭ郢晢ｽｼ陋ｻ・ｶ驍上・
| 陋ｻ・ｶ驍上・骭・| 郢ｧ・ｫ郢晢ｽｩ郢晢｣ｰ | 陷ｿ繧峨・陷医・| ON DELETE | ON UPDATE |
|:--|:--|:--|:--|:--|
| `FK_web_login_challenge_user` | `user_id` | `dbo.user(uuid)` | NO ACTION | NO ACTION |

### CHECK 陋ｻ・ｶ驍上・
| 陋ｻ・ｶ驍上・骭・| 郢ｧ・ｫ郢晢ｽｩ郢晢｣ｰ | 隴夲ｽ｡闔会ｽｶ | 髫ｱ・ｬ隴上・|
|:--|:--|:--|:--|
| `CK_web_login_challenge_failed_attempts` | `failed_attempts` | `>= 0` | 陞滂ｽｱ隰ｨ諤懷ｱ楢ｬｨ・ｰ邵ｺ・ｮ髮具｣ｰ隰ｨ・ｰ闖ｫ譎擾ｽｭ蛟･・帝ｫｦ・ｲ邵ｺ繝ｻ|
| `CK_web_login_challenge_expiry` | `expires_at` | `expires_at > issued_at` | 隴帷甥譟題ｭ帶ｻ・応邵ｺ讙主験髯ｦ譴ｧ蠕玖ｭ弱ｅ・育ｹｧ髮・ｽｾ蠕後堤ｸｺ繧・ｽ狗ｸｺ阮吮・ |

### 郢昴・繝ｵ郢ｧ・ｩ郢晢ｽｫ郢昜ｺ･螳幃ｏ繝ｻ
| 陋ｻ・ｶ驍上・骭・| 郢ｧ・ｫ郢晢ｽｩ郢晢｣ｰ | 陋滂ｽ､ |
|:--|:--|:--|
| `DF_web_login_challenge_failed_attempts` | `failed_attempts` | `0` |

---

## 郢ｧ・､郢晢ｽｳ郢昴・繝｣郢ｧ・ｯ郢ｧ・ｹ陞ｳ螟ゑｽｾ・ｩ

| 郢ｧ・､郢晢ｽｳ郢昴・繝｣郢ｧ・ｯ郢ｧ・ｹ陷ｷ繝ｻ| 郢ｧ・ｫ郢晢ｽｩ郢晢｣ｰ | 驕橸ｽｮ陋ｻ・･ | 騾包ｽｨ鬨ｾ繝ｻ|
|:--|:--|:--|:--|
| `PK_web_login_challenge` | `challenge_id` | CLUSTERED繝ｻ莠包ｽｸ・ｻ郢ｧ・ｭ郢晢ｽｼ繝ｻ繝ｻ| 闕ｳ・ｻ郢ｧ・ｭ郢晢ｽｼ隶諛・ｽｴ・｢ |
| `IX_web_login_challenge_user_active` | `user_id`, `expires_at`, `consumed_at`, `revoked_at` | NONCLUSTERED | 陷ｷ蠕｡・ｸﾂ user 邵ｺ・ｮ隴幢ｽｪ闖ｴ・ｿ騾包ｽｨ郢昶・ﾎ慕ｹ晢ｽｬ郢晢ｽｳ郢ｧ・ｸ陞滂ｽｱ陷会ｽｹ |
| `IX_web_login_challenge_code_hash` | `login_code_hash` | NONCLUSTERED | 陷茨ｽ･陷牙ｸ吶＆郢晢ｽｼ郢晉判・､諛・ｽｨ・ｼ |
| `IX_web_login_challenge_expires_at` | `expires_at` | NONCLUSTERED | 隴帶ｻ・応陋ｻ繝ｻ・瑚ｬ励・蜍・|

---

## DDL

```sql
CREATE TABLE [dbo].[web_login_challenge] (
    [challenge_id]     UNIQUEIDENTIFIER NOT NULL,
    [user_id]          UNIQUEIDENTIFIER NOT NULL,
    [login_code_hash]  NVARCHAR(256)    NOT NULL,
    [issued_at]        DATETIME2(3)     NOT NULL,
    [expires_at]       DATETIME2(3)     NOT NULL,
    [consumed_at]      DATETIME2(3)         NULL,
    [revoked_at]       DATETIME2(3)         NULL,
    [failed_attempts]  INT              NOT NULL CONSTRAINT [DF_web_login_challenge_failed_attempts] DEFAULT (0),
    [issued_by_server] NVARCHAR(50)     NOT NULL,
    [created_at]       DATETIME2(3)     NOT NULL,

    CONSTRAINT [PK_web_login_challenge] PRIMARY KEY CLUSTERED ([challenge_id]),
    CONSTRAINT [FK_web_login_challenge_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_web_login_challenge_failed_attempts] CHECK ([failed_attempts] >= 0),
    CONSTRAINT [CK_web_login_challenge_expiry] CHECK ([expires_at] > [issued_at])
);
GO

CREATE NONCLUSTERED INDEX [IX_web_login_challenge_user_active]
    ON [dbo].[web_login_challenge] ([user_id], [expires_at], [consumed_at], [revoked_at]);
GO

CREATE NONCLUSTERED INDEX [IX_web_login_challenge_code_hash]
    ON [dbo].[web_login_challenge] ([login_code_hash]);
GO

CREATE NONCLUSTERED INDEX [IX_web_login_challenge_expires_at]
    ON [dbo].[web_login_challenge] ([expires_at]);
GO
```

---

## 騾包ｽｨ鬨ｾ繝ｻ
| 騾包ｽｨ鬨ｾ繝ｻ| 髫ｱ・ｬ隴上・|
|:--|:--|
| WEB 郢晢ｽｭ郢ｧ・ｰ郢ｧ・､郢晢ｽｳ郢ｧ・ｳ郢晢ｽｼ郢晄・・ｿ譎擾ｽｭ繝ｻ| Plugin 驍ｨ讙守ｽｰ邵ｺ・ｧ騾具ｽｺ髯ｦ蠕鯉ｼ邵ｺ貅倥＆郢晢ｽｼ郢晏ｳｨ繝ｻ郢昜ｸ翫Ε郢ｧ・ｷ郢晢ｽ･郢ｧ蜑・ｽｿ譎擾ｽｭ蛟･笘・ｹｧ繝ｻ|
| 闕ｳﾂ陜玲ｨ｣蜑樒ｹｧ鬆托ｽｶ驛・ｽｲ・ｻ | `consumed_at` 邵ｺ・ｫ郢ｧ蛹ｻ・願惺蠕個ｧ郢ｧ・ｳ郢晢ｽｼ郢晏ｳｨ繝ｻ陷讎願懸騾包ｽｨ郢ｧ蟶昜ｺ溽ｸｺ繝ｻ|
| 陷蜥ｲ蛹ｱ髯ｦ譴ｧ蜃ｾ陞滂ｽｱ陷会ｽｹ | `revoked_at` 邵ｺ・ｫ郢ｧ蛹ｻ・願惺蠕｡・ｸﾂ user 邵ｺ・ｮ隴鯉ｽｧ郢ｧ・ｳ郢晢ｽｼ郢晏ｳｨ・定ｾ滂ｽ｡陷会ｽｹ陋ｹ謔ｶ笘・ｹｧ繝ｻ|
| 隴帶ｻ・応陋ｻ繝ｻ・碁ｂ・｡騾・・| `expires_at` 邵ｺ・ｫ郢ｧ蛹ｻ・企￥・ｭ陷ｻ・ｽ郢ｧ・ｳ郢晢ｽｼ郢晏ｳｨ竊堤ｸｺ蜉ｱ窶ｻ隰・ｽｱ邵ｺ繝ｻ|
| 髫ｧ・ｦ髯ｦ謔溷ｮ幃ｫｯ繝ｻ| `failed_attempts` 邵ｺ・ｫ郢ｧ蛹ｻ・願怦・･陷牙ｸ幢ｽ､・ｱ隰ｨ諤懷ｱ楢ｬｨ・ｰ郢ｧ蝣､・ｮ・｡騾・・笘・ｹｧ繝ｻ|

---

## 鬮｢・｢鬨ｾ・｣髫ｪ・ｭ髫ｪ繝ｻ
| 驕橸ｽｮ陋ｻ・･ | 郢昜ｻ｣縺・|
|:--|:--|
| API | `00_docs/20_API髫ｪ・ｭ髫ｪ蝓溷ｶ・feature/24-web-auth` |
| Plugin | `00_docs/10_郢晏干ﾎ帷ｹｧ・ｰ郢ｧ・､郢晢ｽｳ髫ｪ・ｭ髫ｪ蝓溷ｶ・feature/24-web-auth` |
| Web | `00_docs/30_WEB髫ｪ・ｭ髫ｪ蝓溷ｶ・feature/01-web-auth` |
