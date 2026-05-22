# MasterData API

filebase の YAML マスタを `MasterDataDB` へ同期する Seeder の管理 API。

API/プラグインが参照する静的マスタは `MasterDataDB` に保持され、Seeder が filebase からの差分を反映する。
同期フローの詳細は `00_docs/40_DB設計書/master-data/MasterDataDB同期フロー.md` を参照。

## POST `/api/master-data/seed`

filebase から `MasterDataDB` を同期する。

### クエリパラメータ

| 名前 | 必須 | 既定 | 説明 |
|:--|:--|:--|:--|
| `mode` | 任意 | `diff` | `diff`: ハッシュ差分のみ反映。`rebuild`: entry/reference を全削除して再投入。 |

### 動作

1. `master_data_seed_run` に `trigger_type=SEEDER_API`, `status=RUNNING` を登録。
2. `config.yml` の source 定義を解決し、YAML を走査。
3. `source_file_hash` 一致は skip、差分は `master_data_entry` を upsert（論理削除済みは復活）。
4. filebase から消えた entry は `is_deleted=1`（物理削除しない）。
5. `ref:` を `master_data_reference` へ展開し、必須参照の未解決を検証。
6. 成功で `SUCCEEDED`、失敗で `FAILED` に更新。

### レスポンス

| ステータス | 説明 |
|:--|:--|
| `200 OK` | 同期成功。`MasterDataSeedResultResponse` を返す。 |
| `422 Unprocessable Entity` | YAML 構文エラーや必須参照未解決により同期失敗。`MasterDataSeedResultResponse`（`status=FAILED`）を返す。 |

`MasterDataSeedResultResponse`:

| フィールド | 説明 |
|:--|:--|
| `seedRunId` | Seeder 実行 ID |
| `triggerType` | `STARTUP` / `SEEDER_API` / `MANUAL` |
| `status` | `SUCCEEDED` / `FAILED` |
| `fileCount` | 走査した YAML 数 |
| `upsertedCount` | INSERT/UPDATE 件数 |
| `deletedCount` | 論理削除件数 |
| `skippedCount` | ハッシュ一致で更新不要だった件数 |
| `errorMessage` | 失敗理由（失敗時のみ） |
| `warnings` | 任意参照の未解決など、失敗扱いにしない警告 |

## GET `/api/master-data/seed-runs`

Seeder の実行履歴を新しい順に取得する。

### クエリパラメータ

| 名前 | 必須 | 既定 | 説明 |
|:--|:--|:--|:--|
| `limit` | 任意 | `20` | 取得件数。1〜100 にクランプされる。 |

### レスポンス

`200 OK` — `MasterDataSeedRunResponse` の配列。

## GET `/api/master-data/health`

`MasterDataDB` の参照可能状態を取得する。

### レスポンス

`200 OK` — `MasterDataHealthResponse`:

| フィールド | 説明 |
|:--|:--|
| `status` | `ok` / `empty`（entry なし）/ `degraded`（直近実行が失敗） |
| `activeEntryCount` | 有効な entry 件数 |
| `lastSeedRunId` | 直近 Seeder 実行 ID |
| `lastSeedRunStatus` | 直近 Seeder 実行ステータス |
| `lastSucceededAt` | 直近成功実行の終了日時 |

## 起動時同期

API 起動時、`MasterData:AutoSeedOnStartup=true` または `MasterDataDB` が空の場合に
`trigger_type=STARTUP` で Seeder を自動実行する（`MasterDataSeedHostedService`）。
