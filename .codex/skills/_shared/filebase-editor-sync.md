# Filebase 定義とローカル編集サイトの同期

`40_filebase` の定義契約、またはその読込・解釈・成長計算を変更するときに読む。ローカル編集サイトは `60_tool/skilltree-editor` にあり、マスターデータ編集と既存スキルツリー編集を同じアプリで扱う。

## 同期する変更

- YAML / JSON のキー、型、必須条件、既定値、列挙値、参照形式・対象、ID / ファイル名 / 表示名の規則。
- Plugin / API が読む値の意味、変換、制約、実装固有 `params` の仕様。ドキュメントの変更がなくても、実行時の契約が変わるなら対象にする。
- 装備ステータスの比較条件・値の意味、プレイヤー / クラスの必要経験値・レベル成長・ステータス計算。

既存契約内でマスターを追加したり、個別の性能値・名前・説明文を調整したりするだけなら、編集サイトのソースを機械的に変更しない。動的読込でフォーム・候補・比較・グラフへ反映されることと、今回のデータの整合を確認する。共有カタログの変更は既存の codegen 手順も維持する。

## 確認元と変更先

対象の `40_filebase/**/docs.*.YAMLスキーマ定義.md`、JSON Schema、近隣マスター、Plugin / API の読込・変換元を対応付ける。資料と実装の不一致を推測で埋めず、実行時に採用される型・値・意味まで追う。

以下は `60_tool/skilltree-editor/` からの相対パス。すべてを毎回変更するのではなく、影響する箇所を同じ変更で更新する。

| 変更内容 | 確認・更新先 |
|:--|:--|
| カテゴリ、フィールド説明・型・必須・列挙、初期値、候補 | `src/SkillTreeEditor.Server/Services/MasterDataCatalog.cs`。定義から動的生成される項目は元資料を修正し、反映結果を確認する。特殊規則だけコードも更新する |
| 保存先・ID・ファイル名・参照・保存時の制約 | `src/SkillTreeEditor.Server/Services/` 配下の `MasterDataPaths.cs`、`MasterDataService.cs`、`MasterDataValidation.cs`、`MasterDataCodec.cs` |
| Server / Client のデータ契約 | `src/SkillTreeEditor.Server/Models/MasterDataContracts.cs`、`src/SkillTreeEditor.Server/Endpoints/MasterDataEndpoints.cs`、`src/SkillTreeEditor.Client/src/types/masterData.ts`、`src/SkillTreeEditor.Client/src/api/masterDataApi.ts` |
| 再帰フォーム、型表示、日本語説明、候補入力、原稿編集・差分 | `src/SkillTreeEditor.Client/src/components/` 配下の `MasterDataForm.tsx`、`MasterDataEditor.tsx`、`MasterDataDiff.tsx` |
| 装備比較・レベル成長グラフ | `src/SkillTreeEditor.Server/Services/MasterAnalyticsService.cs`、`src/SkillTreeEditor.Client/src/data/masterAnalytics.ts`、`src/SkillTreeEditor.Client/src/components/MasterAnalytics.tsx` |
| スキルツリー固有の Schema・ノード・配置・効果 | `src/SkillTreeEditor.Server/Services/` 配下の `SchemaCatalog.cs`、`ValidationService.cs` と、`src/SkillTreeEditor.Client/src/components/` 配下の `SchemaForm.tsx`、`NodeEditor.tsx`、`src/SkillTreeEditor.Client/src/data/`、`src/SkillTreeEditor.Client/src/state/` |

画面の固定キー、項目説明、数値 / 文字列などの型表示は日本語で説明する。保存するキー・ID・参照・列挙値は元の表記を保つ。`implementationId`、RPC、実装固有 `params` の名前などは無理に翻訳せず、確認できる型・意味だけ説明する。不明な自由形式項目を既知の構造と決めつけたり、保存時に削除したりしない。

成長グラフの式を変更する際は、Plugin の `AccountService.requiredExperienceForNextLevel` / `stableHash`、`PlayerClassService.requiredClassExperienceForNextLevel` / `getStatusBonus` など、実際に使われる代入元まで確認する。UUID・職業・経験値配分・転職・転生に依存する結果や仮定を、単一の確定値として表示しない。

## 完了確認

契約変更を `40_filebase` だけの直接作成で完了させない。ソース変更が必要なら `$astralrecord-code-version-commit-develop` で定義・実装・編集サイトを一貫して扱う。すでに統合入口の担当として作業中なら、編集サイトも所有範囲に含めて続ける。

- 変更したカテゴリで既存マスターの読込と新規 / 更新のフォーム・原稿・検証・保存を確認し、対象のキー・型・必須・候補・命名・参照が新しい契約に合うことを確認する。
- 共通の読込・変換・保存処理を変更した場合は、未知キー、コメント、参照、既存データが失われないことを対象テストで確認する。スキルツリーと共通処理を変更した場合は既存ノード / 構造編集も確認する。
- `src/SkillTreeEditor.Client` で `npm run lint`、`npm test`、`npm run build`、編集サイトのルートで `dotnet test SkillTreeEditor.slnx`、`dotnet build SkillTreeEditor.slnx` を実行する。依存関係が未導入なら先に `npm ci` を使う。実行出力と警告は統合入口の品質ゲートに従う。
- 報告に契約の変更内容、編集サイトの更新先 / 動的反映を確認した結果、実施した検証を含める。編集サイトのソース変更が不要だった場合も、反映元と確認結果を示す。
