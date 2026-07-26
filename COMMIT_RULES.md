# コミットルール

AstralRecord モノレポ全体のコミット運用ルールです。

## 基本方針

- 通常の作業コミット先ブランチは `develop` とする。
- コミット前に `git status --short --branch` と差分を確認し、今回の作業に関係するファイルだけを stage する。
- `git add .` や `git add -A` で無差別に stage しない。
- 既に stage 済みの変更がある場合も内容を確認し、今回のコミットに含めるべきでないものは stage から外す。
- 複数の目的が混在している場合は、目的ごとにコミットを分ける。

## コミットしないもの

次のようなローカル環境依存、生成物、秘密情報を含み得るファイルは原則コミットしない。

### ビルド成果物・生成物

- `target/`
- `build/`
- `out/`
- `bin/`
- `obj/`
- `.gradle/`
- `node_modules/`

### IDE・ローカルツール設定

- `.idea/`
- `.vscode/`
- `.vs/`
- `.settings/`
- `.obsidian/`
- `.classpath`
- `.project`
- `.factorypath`
- `.claude/`

### 開発環境・秘密情報を含み得る設定

- `.env`
- `.env.*`
- `*.secret.*`
- `*secrets*.json`
- `local.settings.json`
- `appsettings.Development.json`
- `appsettings.Local.json`
- `20_api/AstralRecordApi/AstralRecordApi/appsettings.Development.json`

### 一時ファイル・ログ

- `*.log`
- `*.tmp`
- `*.bak`
- `.DS_Store`
- `Thumbs.db`

例外として、テンプレートや共有ルールとして明示的に管理するファイルはコミットしてよい。例: `.env.example`, `.gitignore`, `.gitattributes`, `.editorconfig`, `.agents/`, `.github/`, `.codex/skills/`。

`generate-status-types.ps1`が共有ステータスカタログから生成するKotlin/C#/TypeScriptソースは、プラットフォーム間の型同期を保証するための管理対象ソースとして例外的にコミットする。生成物を直接編集せず、YAMLと生成物を同じcommitへ含める。

## コミットメッセージ

コミットメッセージは、差分の主目的が一目で分かる日本語または英語の命令形・名詞句にする。

推奨形式:

```text
<type>: <summary>
```

`type` は次を目安に選ぶ。

- `feat`: 機能追加・仕様追加
- `fix`: 不具合修正
- `docs`: ドキュメント変更
- `refactor`: 振る舞いを変えない整理
- `test`: テスト追加・修正
- `build`: ビルド・依存関係・CI 関連
- `chore`: 開発補助・運用・メタデータ変更

例:

```text
docs: 設計書レビュー用skillを追加
chore: develop向けコミット手順を追加
fix: プレイヤー退出時のキャッシュ削除順を修正
```
