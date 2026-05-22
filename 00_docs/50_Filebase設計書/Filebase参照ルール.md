# Filebase参照ルール

## 1. 目的

YAML 間の参照を `ref:` で表現し、Seeder が MasterDataDB へ投入する前に参照整合性を検証する。

## 2. 参照 prefix

| prefix | aliases | reference_type | 参照先 |
|:--|:--|:--|:--|
| `item:` | `im` | `item` | item |
| `loot_table:` | `lt` | `loot.table` | loot table |
| `loot_pool:` | `lp` | `loot.pool` | loot pool |
| `buff:` | `bf` | `buff` | buff |
| `class:` | `cs` | `class` | class |
| `skill:` | `sk` | `skill` | skill |
| `set:` | `st` | `set_effect` | equipment set effect |
| `recipe:` | `rc` | `recipe` | recipe |
| `mob:` | `mb` | `mob` | mob |

## 3. 記法

推奨:

```yaml
itemId:
  ref: item:magic_crystal
```

短縮 alias:

```yaml
itemId:
  ref: im:magic_crystal
```

直接 ID を許可するフィールドでも、他 source 参照の場合は `ref:` を優先する。

## 4. Seeder の正規化

Seeder は `ref:` を以下のように扱う。

| 入力 | 正規化 |
|:--|:--|
| `ref: item:magic_crystal` | `reference_type = item`, `reference_id_value = magic_crystal` |
| `ref: im:magic_crystal` | `reference_type = item`, `reference_id_value = magic_crystal` |
| `ref: loot_pool:starter_pool` | `reference_type = loot.pool`, `reference_id_value = starter_pool` |

`payload_json` には API が扱いやすい ID 形式で格納し、元の `ref:` は `master_data_reference` に保持する。

## 5. 必須参照

| 参照元 | 参照先 | 必須 |
|:--|:--|:--:|
| recipe ingredient/result | item | ○ |
| loot.pool contents | item | ○ |
| loot.table pools | loot.pool | ○ |
| item consumable effect | buff | △ |
| equipment set | set_effect | △ |
| class starter/level skills | skill | △ |

△ は仕様上 optional なフィールドに限り任意参照として扱える。フィールド自体が存在する場合は原則必須参照とする。

## 6. 未解決参照の扱い

| 種別 | Seeder 結果 | API 参照 |
|:--|:--|:--|
| 必須参照未解決 | `FAILED` | 更新しない |
| 任意参照未解決 | `SUCCEEDED` + warning | 該当参照を欠落扱い |
| alias 未定義 | `FAILED` | 更新しない |

## 7. MasterDataDB 反映先

参照情報は `MasterDataDB.dbo.master_data_reference` に登録する。  
詳細は [[MasterDataDBテーブル一覧]] と `40_database/MasterDataDB/dbo.master_data_reference/master_data_reference.md` を参照する。
