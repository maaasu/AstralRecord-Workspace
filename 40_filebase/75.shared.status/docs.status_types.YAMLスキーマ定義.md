# Status Type YAML スキーマ定義

ステータスID・日本語表示名・カテゴリ・表示書式を、Plugin/API/Webで共有するためのカタログです。

## 正本

- マスタ: `v1.status_types.yml`
- JSON Schema: `schemas/status-types.v1.schema.json`
- 生成入口: `E:\AstralRecord-Workspace\60_tool\generate-status-types.ps1`
- BAT入口: `E:\AstralRecord-Workspace\60_tool\07-generate-status-types.bat`

各プラットフォームの型は生成物であり、直接編集しません。YAMLを変更した後に生成スクリプトを実行し、生成物も同じcommitへ含めます。

## ルート

| キー | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `schemaVersion` | Integer | 必須 | 現在は`1` |
| `categories` | List\<Category\> | 必須 | ステータスカテゴリ |
| `statuses` | List\<Status\> | 必須 | ステータス定義。記載順を各プラットフォームの列挙順に使用 |

## Status

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `id` | String | 必須 | - | 大文字スネークケースの不変ID。削除済みIDを再利用しない |
| `displayName` | String | 必須 | - | 日本語表示名 |
| `description` | String | 必須 | - | ステータスの用途・効果を説明する日本語文 |
| `category` | String | 必須 | - | `categories[].id`への参照 |
| `suffix` | String | 任意 | 空文字 | 表示単位 |
| `decimalPlaces` | Integer | 任意 | `0` | 表示時の小数桁数（0～6） |
| `supportsRange` | Boolean | 任意 | `true` | 最小値・最大値の範囲保持を許可するか |

IDは既存filebaseが使用する`MAX_HEALTH`などを維持します。表示名の変更は許可しますが、ID変更が必要な場合は参照元を一括移行し、旧IDを別用途へ再利用しません。

## 生成物

| Platform | 出力 |
| --- | --- |
| Kotlin / Plugin | `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/model/StatusType.kt` |
| C# / API | `20_api/AstralRecordApi/AstralRecordApi/Models/StatusType.generated.cs` |
| TypeScript / Skill Tree Editor | `60_tool/skilltree-editor/src/SkillTreeEditor.Client/src/data/statusTypes.generated.ts` |

通常生成:

```powershell
.\60_tool\generate-status-types.ps1
```

または`60_tool\07-generate-status-types.bat`を実行します。

生成漏れ検査:

```powershell
.\60_tool\generate-status-types.ps1 -Check
```
