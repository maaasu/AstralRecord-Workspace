# AstralRecord Monorepo Guide

このファイルはモノレポ全体の統括ガイドです。  
作業を始める前に対象プロジェクトを特定し、このファイルに加えて対象プロジェクトの `Read Next` を読んでください。

## 対象プロジェクト

| Project | Role | Main Stack | Read Next |
|:--|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft Plugin | Java, Kotlin, Paper/Spigot, Maven | `PLUGIN_GUIDE.md` / `$astralrecord-code` |
| `10_plugin/AstralArchitect/` | AI-assisted Minecraft building Plugin | Java, Paper, FAWE, Maven, Python | `10_plugin/AstralArchitect/AGENTS.md` / `$astralarchitect-builder` |
| `20_api/AstralRecordApi/` | REST API | ASP.NET Core, C#, SQL Server | `API_GUIDE.md` / `$astralrecord-code` |
| `30_web/AstralRecordWeb/` | Web Site | ASP.NET Core Razor Pages | `30_web/AstralRecordWeb/AGENTS.md` |
| `00_docs/40_Database設計書/` | SQL Server schema / table docs | SQL Server, Markdown | `00_docs/40_Database設計書/README.md` |
| `40_filebase/` | File-based master data | YAML, Markdown | `00_docs/50_Filebase設計書/README.md` / `40_filebase/AGENTS.md` |
| `50_resourcepack/` | Minecraft Resource Pack（現在は開発停止・将来用に保持） | JSON, PNG, PowerShell | `50_resourcepack/AGENTS.md` |

実行環境や配置先の確認が必要な場合は、`E:\AstralRecord-Workspace` に加えて `\\DEVICE_SERVER\server` も一度確認してください。アクセスできない場合は、その場所の参照をあきらめて作業を継続し、そこがどうしても必要で作業を進められない場合だけユーザーに確認してください。

## 対象判定ルール

1. パスが明示されている場合は、そのパスが属するプロジェクトを対象にする。
2. 技術スタックや用語で判断できる場合は、その領域のプロジェクトを対象にする。
3. 複数プロジェクトにまたがる場合は、対象を分けて扱う。
4. 判定できない場合は、作業を止めて確認する。

確認が必要なときは次の形式を使ってください。

```text
1. 質問
対象プロジェクトを教えてください。

- AstralRecord（Minecraft Plugin / Java）
- AstralRecordApi（REST API / ASP.NET Core）
- AstralRecordWeb（Web サイト / Razor Pages）
- Database（SQL Server DB / テーブル定義）
- Filebase（file 系マスタデータ）
- Resourcepack（Minecraft Resource Pack。将来用に保持、現在は無視）
```

## 共通方針

- まず対象プロジェクトを特定してから作業する。
- 実装ルールは対象プロジェクトの `Read Next` を優先する。
- ソースコードだけで運用ルールを推測しない。文書化された `AGENTS.md`、`README.md`、skill 参照を優先する。
- SQL Server の DB / テーブル定義は `00_docs/40_Database設計書/`、file 系マスタデータは `40_filebase/`、filebase の設計方針は `00_docs/50_Filebase設計書/` に分けて扱う。

## 文字コード

- Windows PowerShell で UTF-8 のテキストファイルを読む場合、`Get-Content` には必ず `-Encoding UTF8` を指定する。
- `Get-Content` は次の形式を使用する: `Get-Content -Raw -Encoding UTF8 -LiteralPath '<absolute-path>'`
- `Select-String` でファイルを読む場合も、必ず `-Encoding UTF8` を指定する。
- `$OutputEncoding` やコンソール出力設定の変更だけでは入力時の文字化けを防げないため、ファイル読み込み側のエンコーディング指定を優先する。
- 既存ファイルを文字コード変換する目的で再保存してはならない。

## Skills 実行ガイド（E:\AstralRecord-Workspace）

本モノレポで skill を使う場合は、`E:\AstralRecord-Workspace\.codex\skills\README.md` を正本として扱うこと。  
以下は、テンプレート実行時に迷わないための最小手順です。

### 自動選択ルール

ユーザーが skill 名を明示していない場合でも、実装修正・コード修正・設計書反映・filebase マスタ作成など、未コミット差分が発生する作業が必要だと判断できるときは、統合入口として `$astralrecord-code-version-commit-develop` を優先して使う。
この統合入口は task worktree 作成、対象 worker skill 実行、commit、必要に応じた develop 反映までをつなぐための既定ルートとする。

すでに task worktree が用意されており、現在の差分を commit するだけなら `$astralrecord-commit-current-diff` を使う。branch / worktree 作成、finalize、develop merge、cleanup など git 運用そのものが明示されている場合は `$astralrecord-git-worktree-develop` を使う。

### 実行前チェック

1. 対象プロジェクトを先に判定する（本ファイルの「対象判定ルール」を使用）。
2. 対象が `10_plugin/AstralRecord` の場合、ルート `PLUGIN_GUIDE.md` と `$astralrecord-code` を使う。
3. 対象が `10_plugin/AstralArchitect` の場合、`10_plugin/AstralArchitect/AGENTS.md`を先に読み、AI候補編集には`$astralarchitect-builder`を使う。
4. 対象が `20_api/AstralRecordApi` の場合、ルート `API_GUIDE.md` と `$astralrecord-code` の API 参照を使う。
5. 使う skill は `E:\AstralRecord-Workspace\.codex\skills\README.md` の正本ルールに従って判定する。
6. 実装差分が発生する作業では、個別 worker skill を直接使う前に `$astralrecord-code-version-commit-develop` を入口にできるか確認する。

### テンプレート実行手順

1. README の「汎用テンプレート」をベースに依頼文を作る。
2. `<...>` は必ず実パスに置換してから実行する。
3. skill 名は必ず `$skill-name` 形式で明示する（例: `$astralrecord-docs-review`）。
4. パスは曖昧語ではなく絶対パスで指定する（例: `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\07-status`）。

### Skill 判定ルール（追加追記不要）

1. `E:\AstralRecord-Workspace\.codex\skills\` 配下のサブディレクトリを skill 候補として扱う。
2. `<skill-directory>\SKILL.md` が存在するものを有効 skill として扱う。
3. 利用時は `SKILL.md` の frontmatter（`name` / `description`）を正として解釈する。
4. `description` は自動選択の主要な判断材料として扱い、実装差分が出る依頼では統合入口 `$astralrecord-code-version-commit-develop` の説明を優先して照合する。
5. 新しい skill 追加時は、この `AGENTS.md` に個別追記しない。配置要件（`<skill-directory>\SKILL.md`）を満たせば参照対象とする。

### 汎用実行テンプレート

```text
Use $<skill-name> to <task> for <absolute-path> and report the result.
```
