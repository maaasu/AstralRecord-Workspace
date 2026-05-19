# AstralRecord Monorepo Guide

このファイルはモノレポ全体の統括ガイドです。  
作業を始める前に対象プロジェクトを特定し、このファイルに加えて対象プロジェクト直下の `AGENTS.md` を読むこと。

## 対象プロジェクト

| Project | Role | Main Stack | Read Next |
|:--|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft Plugin | Java, Kotlin, Paper/Spigot, Maven | `10_plugin/AstralRecord/AGENTS.md` |
| `20_api/AstralRecordApi/` | REST API | ASP.NET Core, C#, SQL Server | `20_api/AstralRecordApi/AGENTS.md` |
| `30_web/AstralRecordWeb/` | Web Site | ASP.NET Core Razor Pages | `30_web/AstralRecordWeb/AGENTS.md` |
| `40_database/` | SQL Server schema / table docs | SQL Server, Markdown | `40_database/AGENTS.md` |
| `50_filebase/` | File-based master data | YAML, Markdown | `50_filebase/AGENTS.md` |
| `60_resourcepack/` | Minecraft Resource Pack | JSON, PNG, PowerShell | `60_resourcepack/AGENTS.md` |

実行環境や配置先の確認が必要な場合は、`E:\AstralRecord-Workspace` に加えて `\\DEVICE_SERVER\server` も確認対象に含めること。

## 対象判定ルール

1. パスが明示されている場合は、そのパスが属するプロジェクトを対象にする。
2. 技術スタックや単語で判断できる場合は、その領域のプロジェクトを対象にする。
3. 複数プロジェクトにまたがる場合は、対象を分けて扱う。
4. 判定できない場合は作業を止めて確認する。

確認が必要なときは次の形式を使うこと。

```text
1. 質問
対象プロジェクトを教えてください。

- AstralRecord（Minecraft Plugin / Java）
- AstralRecordApi（REST API / ASP.NET Core）
- AstralRecordWeb（Web サイト / Razor Pages）
- Database（SQL Server DB / テーブル定義）
- Filebase（file 系マスタデータ）
- Resourcepack（Minecraft Resource Pack）
```

## 共通方針

- まず対象プロジェクトを特定してから作業する。
- 実装ルールは対象プロジェクト直下の `AGENTS.md` を優先する。
- `.agents/prompts/` がある場合は、作業内容に対応する補助プロンプトも読む。
- ソースコードだけで運用ルールを推測しない。明文化された補助プロンプトを優先する。
- SQL Server の DB / テーブル定義は `40_database/`、file 系マスタデータは `50_filebase/` に分けて扱う。

## Skills 実行ガイド（E:\AstralRecord-Workspace）

本モノレポで skill を使う場合は、`E:\AstralRecord-Workspace\.codex\skills\README.md` を正本として扱うこと。  
以下は、テンプレート実行時に迷わないための最小手順。

### 実行前チェック

1. 対象プロジェクトを先に判定する（本ファイルの「対象判定ルール」を使用）。
2. 対象が `10_plugin/AstralRecord` の場合、`10_plugin/AstralRecord/AGENTS.md` と必要な `.agents/prompts/*.md` を読む。
3. 使う skill は `E:\AstralRecord-Workspace\.codex\skills\README.md` の「正本ルール」に従って判定する。

### テンプレート実行手順

1. README の「汎用テンプレート」をベースに依頼文を作る。
2. `<...>` は必ず実パスに置換してから実行する。
3. skill 名は必ず `$skill-name` 形式で明示する（例: `$astralrecord-docs-review`）。
4. パスは曖昧語ではなく絶対パスで指定する（例: `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status`）。

### Skill 判定ルール（追加追記不要）

1. `E:\AstralRecord-Workspace\.codex\skills\` 配下のサブディレクトリを skill 候補として扱う。
2. `<skill-directory>\SKILL.md` が存在するものを有効 skill として扱う。
3. 利用時は `SKILL.md` の frontmatter（`name` / `description`）を正として解釈する。
4. 新しい skill 追加時は、この `AGENTS.md` に個別追記しない。配置規約（`<skill-directory>\SKILL.md`）を満たせば参照対象とする。

### 汎用実行テンプレート

```text
Use $<skill-name> to <task> for <absolute-path> and report the result.
```
