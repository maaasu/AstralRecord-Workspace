# dbo.player テーブル定義

プレイヤーUUIDを正本キーとする長期保持用の運営管理レコード。Webログイン以外の将来の用途でも同じプレイヤーを識別する。MCIDは変更される表示・検索情報であり、識別キーにはしない。

| カラム | 型 | NULL | 既定値 | 内容 |
|---|---|---|---|---|
| `player_uuid` | `UNIQUEIDENTIFIER` | NO | なし | MinecraftプレイヤーUUID。主キー |
| `mcid` | `NVARCHAR(100)` | NO | なし | 最後に確認したMCID |
| `web_admin` | `BIT` | NO | `0` | Web管理機能の許可。ゲームpermissionと独立 |
| `created_at` | `DATETIME2(3)` | NO | UTC現在日時 | 管理レコード作成日時 |
| `updated_at` | `DATETIME2(3)` | NO | UTC現在日時 | 管理レコード更新日時 |
| `first_web_login_at` | `DATETIME2(3)` | YES | NULL | 初回Webログイン受付日時 |
| `last_web_login_at` | `DATETIME2(3)` | YES | NULL | 最終Webログイン受付日時 |

## 保存・更新契約

- Web利用前に他の運営機能でプレイヤーを登録できるよう、Webログイン日時はNULLを許可する。登録APIや寄付機能は今回追加しない。
- Webログイン時に未登録なら作成し、既存ならMCID・Webログイン日時・更新日時だけを更新する。`created_at`、UUID、既存`web_admin`を変えない。
- 既存レコードの初回Webログイン日時がNULLなら、そのログイン日時を設定する。再ログインで最終日時を過去へ戻さない。
- 管理情報はゲームの`user`・`account`への外部キーやcascadeを持たない。ゲームリセット、キャラクター削除、MCID変更でこのレコードを削除しない。
- ゲームDBからプレイヤーが消えても管理情報・Web管理権限の照会はできる。コード発行には従来どおりゲームDBの登録が必要。
- Web管理機能はリクエストごとに`web_admin`を照会する。初期管理者を自動指定しない。

```sql
UPDATE [ManagementDB].[dbo].[player]
SET web_admin = 1, updated_at = SYSUTCDATETIME()
WHERE player_uuid = '対象プレイヤーUUID';
```

## 制約

- UUID主キー、空白のみMCID禁止、更新日時≧作成日時。
- Webログイン日時は両方NULL、または両方非NULLかつ最終≧初回。
- `mcid`には一意制約を付けない。名前変更や再利用でUUIDを統合しない。
