# dbo.master_data_reference テーブル定義

マスタデータ間の `ref:` 参照を保持するテーブル。  
Seeder が YAML を解析して参照元・参照先を登録し、未解決参照を検出する。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `MasterDataDB` |
| スキーマ名 | `dbo` |
| テーブル名 | `master_data_reference` |
| 完全修飾名 | `dbo.master_data_reference` |
| 主キー | `reference_id` |
| 外部キー参照先 | `dbo.master_data_entry.entry_id` |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `reference_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | 参照レコード ID |
| `from_entry_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 参照元マスタ。参照先: `dbo.master_data_entry.entry_id` |
| `from_master_type` | `NVARCHAR(80)` |  | ○ |  | 参照元種別の冗長保持 |
| `from_master_id` | `NVARCHAR(120)` |  | ○ |  | 参照元 ID の冗長保持 |
| `reference_type` | `NVARCHAR(80)` |  | ○ |  | 参照先種別。例: `item`, `loot.table`, `buff` |
| `reference_id_value` | `NVARCHAR(120)` |  | ○ |  | 参照先 ID |
| `reference_path` | `NVARCHAR(300)` |  |  |  | 参照が出現した JSON パス |
| `is_required` | `BIT` |  | ○ | `1` | 未解決時に Seeder を失敗させるか |
| `sort_order` | `INT` |  | ○ | `0` | 同一マスタ内の参照順 |
| `created_at` | `DATETIME2(3)` |  | ○ |  | レコード作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ○ |  | レコード最終更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 最終更新者 UUID |
| `is_deleted` | `BIT` |  | ○ | `0` | 論理削除フラグ |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_master_data_reference` | `reference_id` | CLUSTERED | 主キー検索 |
| `IX_master_data_reference_from_entry` | `from_entry_id`, `is_deleted` | NONCLUSTERED | 参照元からの取得 |
| `IX_master_data_reference_target` | `reference_type`, `reference_id_value`, `is_deleted` | NONCLUSTERED | 参照先の逆引き・未解決検証 |

---

## 検証方針

| 検証 | 内容 |
|:--|:--|
| 必須参照 | `is_required = 1` の参照先が `master_data_entry` に存在しない場合、Seeder を失敗扱いにする |
| 任意参照 | `is_required = 0` の参照先が存在しない場合、警告として扱う |
| 循環参照 | 直接循環は警告。スキル/バフなど仕様上必要な循環は source ごとに許容設定を検討する |

---

## 用途

| 用途 | 説明 |
|:--|:--|
| 参照整合性 | item -> buff、recipe -> item、loot.table -> loot.pool などの参照を検証する |
| 影響調査 | あるマスタ ID を変更したときの参照元を逆引きする |
| Seeder 差分 | 参照元ファイル更新時に該当 entry の参照だけを入れ替える |
