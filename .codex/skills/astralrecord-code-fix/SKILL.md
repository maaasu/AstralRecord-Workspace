---
name: astralrecord-code-fix
description: astralrecord-code-review のレビュー結果に基づき AstralRecord モノレポのソースコード、実装データ、workspace skill を修正する。AR-CODE 指摘 ID からプラグイン/API/Web/DB/filebase/resourcepack/.codex skills の指摘を、レビュー結果を正として最小変更で解決したい場合に使う。
---

# AstralRecord コード修正

## 基本ルール

レビュー結果に基づき、ソースコード、filebase/resourcepack などの実装隣接データ、または workspace skill 定義を修正し、その修正によって記載内容が変わる `00_docs/` の設計書があれば更新する。新しい設計意図を作らない。指摘が `要確認` または `設計判断待ち` の場合は、依頼で不足している判断が明示されない限り未解決のまま残す。

何を変更するかはレビュー結果を正とする。編集時は対象プロジェクトの文書化されたコーディングルール（ルートガイド、プロジェクトの `README.md` / `AGENTS.md`、`astralrecord-code/references/*`）に従う。編集は最小限にし、無関係なリファクタリングを混在させない。

保存済みレビュー記録を解析または更新する前に、`<task-root>\.codex\skills\_shared\review-record-format.md` を最後まで読む。正規スキーマと更新スクリプトは必須とする。

この skill は、実装修正と対応する設計書同期を一度の作業で扱う。変更が設計書だけの場合に限り `$astralrecord-docs-fix` を使う。レビュー結果のない新規作業は `$astralrecord-code`、`.codex/skills` の場合は `$skill-creator` の対象とする。

## 入力

次のいずれかを受け付ける。

- コード対象パス（ファイル、feature ディレクトリ、またはプロジェクト）と、レビュー結果のパスまたは貼り付けたレビュー本文。
- `AR-CODE-001` のように、会話中にすでに示されたレビュー指摘 ID を参照する依頼。

レビュー結果または指摘の詳細がない場合は、編集前にレビュー結果を求める。

## 必須コンテキスト

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. レビュー結果の `対象範囲` / `確認した範囲`、または各指摘の絶対パスから対象プロジェクトを特定する。
   - `10_plugin/AstralRecord` → Minecraft Plugin（Java/Kotlin、Paper/Spigot、Maven）
   - `10_plugin/AstralArchitect` → AI 支援 Minecraft 建築 Plugin（Java/Paper/FAWE/Python）
   - `20_api/AstralRecordApi` → REST API（ASP.NET Core、C#）
   - `30_web/AstralRecordWeb` → Web（Razor Pages）
   - `40_filebase/` → ファイルベースのマスターデータ（YAML/Markdown）
   - `50_resourcepack/` → Minecraft Resource Pack（JSON/PNG）
   - `00_docs/40_Database設計書/` → SQL Server スキーマ設計書
   - `.codex/skills/` → Workspace skill（Markdown/Python/YAML）
   - `60_tool/` → Workspace の build/deploy/development ツール（PowerShell/C#/TypeScript/BAT）
3. 編集前に対象プロジェクトの文書化されたルールを読む。
   - Plugin: ルートの `PLUGIN_GUIDE.md`、プロジェクトの `README.md` / `AGENTS.md`、`astralrecord-code/references/plugin-code.md`。
   - AstralArchitect: `10_plugin/AstralArchitect/AGENTS.md` と、そこからリンクされたプロジェクトルール。
   - API: ルートの `API_GUIDE.md`、プロジェクトの `README.md` / `AGENTS.md`、`astralrecord-code/references/api-code.md`。
   - Web: ルート `README.md` の「AstralRecord Web」節と `30_web/AstralRecordWeb/AGENTS.md`。
   - Filebase / Resourcepack / Database: ルート `README.md` の該当節と、対象領域の `AGENTS.md` / `README.md`。
   - Workspace skill: `.codex/skills/README.md`、対象の `SKILL.md`、リンクされた references/scripts、`$skill-creator` の指示。
   - Tools: `60_tool/README.md` と、対象にある `AGENTS.md` またはリンクされたツール文書。
4. 指摘が設計書を参照する場合は、コード編集前にその設計書を読み、contract を理解する。
5. 対象 project を特定できない場合は停止し、ルート `AGENTS.md` の project selection question を尋ねる。

## 手順

1. コード対象パスとレビュー元を特定する。
   - `git rev-parse --show-toplevel` で `<task-root>` を解決する。
   - コードまたはレビュー記録を編集する前に、`develop` ではない専用 task branch/worktree を必須とする。ない場合は `develop` に書き込まず、統合 worktree 手順を使う。
   - 提供された記録パスが `E:\AstralRecord-Workspace` 配下なら、`<task-root>` 配下の同じ相対パスに読み替え、その worktree のコピーだけを更新する。
   - 提供された記録が別の task worktree 内にある場合は停止し、その記録の worktree を再利用する統合 review-fix 入口を要求する。修正と正規記録を branch 間で分割しない。
2. `astralrecord-code-review` の報告形式でレビュー結果を解析する。
   - `AR-CODE-*` finding IDs.
   - `種別`, `対象`, `関連箇所`, `根拠`, `問題`, `影響`, `修正方針`, `修正対象候補`, `修正可否`, `確信度`, `修正状態`.
    - `修正スキル入力サマリ`（自動修正候補 / 要確認 / 推奨修正順 / 対象範囲）がある場合はそれも読む。
3. 修正対象の指摘を選ぶ。
   - 既定では `修正可否: 自動修正可` の指摘をすべて修正する。
   - ユーザーが特定の ID を指定した場合は、その ID だけを修正する。
   - 必要な判断がユーザーから与えられない限り、`要確認` または `設計判断待ち` の指摘は修正しない。
   - `推奨修正順` がある場合は、依存する修正が整合した順序で入るよう従う。
4. 各修正に必要な最小限のコードを読む。
   - `対象` とその `関連箇所` のファイル。
   - 挙動を左右する呼び出し元、テスト、fixture、resource ファイル。
   - 既存の enum、ID、repository、DTO、service、helper、message、resource の慣例。
5. 指摘を解消する最小限のコード変更を、次を保ちながら適用する。
   - 周辺の言語、命名、package/layer 構成、DI 方式、エラー処理、テストパターン。
   - プロジェクトの文書化されたコーディングルール。
   - 無関係な挙動。便乗したリファクタリングは行わない。
6. コード編集後、変更された挙動を記載する設計書を特定する。
   - 各指摘の `関連箇所` / `根拠` に記載されたパスから確認する。
   - `00_docs/10_Plugin設計書/feature/`（plugin）、`00_docs/20_API設計書/`（API）、対象プロジェクトの関連領域の文書も確認する。
   - 影響する各設計書に対し、修正後のコードと一致する最小限の編集を行う。必要に応じてメソッドシグネチャ、挙動説明、フィールド定義、状態図を更新する。
   - 修正に必要な範囲を超えて設計書を再構成しない。
7. 編集後、変更箇所を再読し、各修正対象の指摘が解消されていることを確認する。
8. 検証する。
    - 変更したプロジェクトに対して、意味のある最小限の build / test / static-analysis check を実行する。
    - feature/behavior 修正、実行可能 script、schema/data contract、workspace skill logic、複数ファイル修正、security/concurrency/data-integrity 修正では、標準エラーを含む完全な検証出力を取得し、終了コードだけでなく警告も確認する。修正が原因の警告は解消して同じ check を再実行する。残った警告は既存（可能なら現在の local `develop` で確認）か外部/toolchain 起因かを分類し、command、警告概要、分類、理由を報告する。新規の未説明警告または修正起因の警告が残る指摘を修正済みとして扱わない。ただしユーザーが明示的に先送りを承認した場合を除く。
    - Plugin の source/resource 修正では `python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <task-worktree>` を実行し、ID/property のずれ、重複 key、log placeholder の不一致、直接の logger/message 呼び出し、command message helper に渡された文字列リテラルを解消してから修正済みとする。再利用した ID の property 文言が実際の操作を表すことも手動確認する。
   - full build が高コストまたはブロックされる場合は、対象を絞った compile / test / lint check を実行し、未実行のものを報告する。
9. レビュー元が `<task-root>\00_docs\99_資料\レビュー結果` 配下の保存済み記録なら、修正済み状態と派生メタデータだけを次のコマンドで更新する。

```powershell
python <task-root>\.codex\skills\_shared\scripts\update_review_record.py <record-path> --fixed <AR-CODE-IDs>
```

   - 記録の名前変更、メタデータの書き換え、指摘フィールドの削除、指摘本文の要約、指摘順の変更、ID の振り直しを手動で行わない。
   - 元の timestamp、対象パス、`code-review` skill 名を保持する。
   - 回答が提示済みまたは明確に確認できる場合だけ、質問ごとに一度 `--resolve-question '<Q-CODE-ID>=<confirmed answer>'` を追加する。それ以外は省略し、質問を `未確認` のまま残す。
   - 返されたパスを `validate_review_record.py` で再検証し、失敗した場合は記録の更新完了を報告しない。

## 編集時の制約

- 変更は、指摘が明示的に別の場所を指していない限り、コード、実装隣接データ、workspace skill 定義、`00_docs/` 配下の直接影響する設計書に限定する。
- 設計書の編集は修正済み指摘に追跡可能な最小限とする。コード変更に必要な範囲を超えて再構成・書き換え・拡張をしない。
- 複数ファイルへ説明を重複させず、正本の場所を修正することを優先する。
- 指摘が要求していない新しい抽象化、helper、設定 toggle を導入しない。
- `未確認/質問`（`Q-CODE-*`）は、レビュー結果、ユーザーの指定、または必須コンテキストから明確に確認できる場合だけ解決する。それ以外は未解決のまま報告に残す。
- 指摘が明示的に要求しない限り、public API、command 名、message ID、log category、table 名、item ID、resource key を変更しない。
- プロジェクト内ですでに使われている日本語の用語と message 文言を維持する。

## 対象外

- `00_docs/40_Database設計書` 以外の設計書だけの変更 → `$astralrecord-docs-fix`。SQL Server schema docs と workspace skill Markdown はこの skill の実装成果物範囲に含む。
- レビュー結果のない新規作業 → `$astralrecord-code`、`.codex/skills` の場合は `$skill-creator`。
- 大規模リファクタリング。各修正を最小限にし、構造的な再設計は別タスクへ分離する。

## 報告形式

結果は日本語で記載する。

```markdown
## 修正結果
- `AR-CODE-001`: 修正済み - <何を変えたか>
- `AR-CODE-002`: 未対応（要確認） - <必要な確認>

## 変更ファイル

### コード
- `<path>`: <変更概要>

### 設計書
- `<path>`: <変更概要> / なし

## 未対応
- `AR-CODE-002`: <理由>
- `Q-CODE-001`: <理由>

## 検証
- `<command>`: 成功 / 失敗 / 未実行（理由）
- 変更箇所の再確認: 実施
- 設計書参照: <読んだ docs / なし>
- 設計書編集: <編集したdocsパス一覧 / なし>

## 残事項
- なし / <追加のdocs整備や設計判断が必要な項目>
```

## 拡張ポイント

プロジェクト固有の修正観点が増えたら、本文に詰め込まず `references/` に追加する。命名規則:

- `references/plugin-code-fix.md` … `10_plugin/AstralRecord` 固有の修正観点。
- `references/api-code-fix.md` … `20_api/AstralRecordApi` 固有の修正観点。
- `references/web-code-fix.md` … `30_web/AstralRecordWeb` 固有の修正観点。

参照ファイルにはパス検出条件、必読ルールファイル、追加チェック項目、報告書きのテンプレ差分だけを書き、設計書本体や大きなコードを丸ごとコピーしない。
