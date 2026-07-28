# Status 設計

## 役割

Statusは、Plugin・API・開発者用Webツールが共通利用するステータスID、日本語表示名、カテゴリ、表示書式を定義します。

## 正本

- 共有カタログ: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
- スキーマ資料: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\docs.status_types.YAMLスキーマ定義.md`
- 生成入口: `E:\AstralRecord-Workspace\60_tool\generate-status-types.ps1`
- BAT入口: `E:\AstralRecord-Workspace\60_tool\07-generate-status-types.bat`

ステータスIDは大文字スネークケースの不変文字列とし、YAMLの記載順や数値採番をIDとして使用しません。削除したIDは再利用しません。

## 生成と利用

共有カタログから次を生成し、生成物もGit管理します。

- Plugin向けKotlin `StatusType`
- API向けC# `StatusType`と表示メタデータ
- Skill Tree Editor向けTypeScript `StatusTypeId`と表示カタログ

カタログ変更後は`.\60_tool\generate-status-types.ps1`を実行します。`.\60_tool\generate-status-types.ps1 -Check`は生成漏れがある場合に失敗します。

ダメージ計算、カテゴリ色、Minecraft表示などプラットフォーム固有の挙動は、生成対象ごとのコードまたは利用側ロジックで扱います。新しいステータスをゲーム計算へ反映する処理は自動生成の対象外です。

## progression

ゲーム進行に属さないシステム共通契約のため`progression: 0`とします。
