# dbo.network_settings テーブル定義

ProxyのAPI設定以外を保持する単一レコード。API接続先・認証キー・TLS設定は保存しない。

| カラム | 型 | NULL | 内容 |
|---|---|---|---|
| `id` | INT | NO | 主キー、CHECKで1固定 |
| `revision` | INT | NO | 1以上、楽観的同時更新制御 |
| `settings_json` | NVARCHAR(MAX) | NO | ISJSON制約。ロビー・周期・権限UUID・チャンネル一覧 |
| `updated_at_utc` | DATETIME2(3) | NO | 最終更新UTC |
| `updated_by` | UNIQUEIDENTIFIER | YES | Web実行者UUID、bootstrapはNULL |

初回bootstrapは未作成時だけINSERTする。Web更新は期待revisionを照合し、同一トランザクションで監査を追加する。UUID→MCIDの表示情報はJSONへ保存しない。SQL ServerではUPDLOCK/HOLDLOCKで未作成行を含めて直列化する。
