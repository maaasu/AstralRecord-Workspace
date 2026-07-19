# dbo.account_class_progress テーブル定義

アカウントごと・クラスごとのレベルと累計クラス経験値を保持する。`dbo.account.class_id` は現在選択中クラス、`class_level` / `class_experience` は互換表示用の現在クラスミラーとし、本テーブルをクラス進行度の正本とする。

## カラム定義

| カラム名 | データ型 | PK | NotNull | 既定値 | 説明 |
|---|---|:---:|:---:|---|---|
| `account_id` | `UNIQUEIDENTIFIER` | ○ | ○ | - | 所有アカウント UUID（FK → `dbo.account.uuid`） |
| `class_id` | `NVARCHAR(100)` | ○ | ○ | - | クラス ID |
| `level` | `INT` |  | ○ | `1` | 当該クラスのレベル。最小値 `1` |
| `experience` | `BIGINT` |  | ○ | `0` | 当該クラスの累計クラス経験値。負数不可 |
| `updated_at` | `DATETIME2(3)` |  | ○ | - | 最終更新日時 |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ | - | 最終更新者 UUID |

## 制約・インデックス

| 名前 | 種別 | 定義 |
|---|---|---|
| `PK_account_class_progress` | PK | (`account_id`, `class_id`) |
| `FK_account_class_progress_account` | FK | `account_id -> dbo.account(uuid)` / NO ACTION |
| `CK_account_class_progress_class_id_not_blank` | CHECK | `class_id` は空文字不可 |
| `CK_account_class_progress_level` | CHECK | `level >= 1` |
| `CK_account_class_progress_experience` | CHECK | `experience >= 0` |
| `IX_account_class_progress_class_id` | INDEX | (`class_id`) |

## 更新ルール

- クラス経験値獲得時は (`account_id`, `class_id`) 単位で upsert する。
- 転職時は既存行の進行度を復元し、未経験クラスのみ Lv.1 / EXP 0 の行を作成する。
- API は現在選択中クラスの行を `dbo.account.class_level` / `class_experience` にも反映し、旧クライアントとの互換性を保つ。
- 既存データ移行時は `dbo.account` の現在クラス 1 件を初期行としてコピーする。
