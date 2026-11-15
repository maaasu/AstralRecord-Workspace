# Tag Catalog Code Generator

`40_filebase/76.shared.tag/v1.tags.yml`を正本として、Plugin Java、API C#、スキルツリーエディターTypeScriptのタグ定数・表示情報を生成します。同時に`40_filebase`の`tag`、`tags`、`requiredToolTags`を走査し、未定義タグと`appliesTo`不一致を検出します。

リポジトリルートから`60_tool/08-generate-tag-types.bat`を実行してください。整合性を確認するときは`-Check`を指定します。
