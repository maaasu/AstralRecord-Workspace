---
name: astralrecord-code-version-commit-develop
description: AstralRecord で差分を作る実装・設計書反映・本番filebase作成の統合入口。task branch / worktree、対象worker、必要な品質ゲート、commit、rebase、develop反映を対象に応じて実行する。差分のない質問・診断・読み取り専用レビューには使わない。
---

# AstralRecord Code Version Commit Develop

## 目的

差分を作るtaskを、並列編集可能な専用worktreeからdevelop反映まで一貫して扱う。差分作成中のworktree分離は常に維持し、成功したfinalize後のcleanupはGit skillに任せる。対象外の資料・ツール・検証工程だけをtask種別に応じて読み飛ばす。

## 最初の分類

1. `質問・説明・診断・読み取り専用レビュー`で差分が不要なら、このskillを起動せず回答する。
2. 差分が必要なら、先に [task-routing.md](references/task-routing.md) を読み、`対象`、`変更種別`、`Light/Standard gate`、`必要な参照`を決める。
3. ルート `AGENTS.md` と worktree管理参照を読み、対象プロジェクトの `Read Next` と対象workerだけを読む。無関係なskillやguideを読まない。
4. `Standard gate` または workspace skill logic の変更では [quality-gate.md](references/quality-gate.md) を読む。Light gateでは読まない。
5. `40_filebase` の並列作業だけ [parallel-filebase.md](references/parallel-filebase.md) を読む。

## Workerの選択

- 既存 `AR-CODE-*` 指摘の修正 → `$astralrecord-code-fix`
- 既存 `AR-DOC-*` 指摘の修正 → `$astralrecord-docs-fix`
- Minecraft内の職業・戦闘スキル → `$astralrecord-skill-author`
- 本番向け `40_filebase` マスタ作成 → `$astralrecord-master-data-author`
- `.codex/skills` の定義・参照・script・`agents/openai.yaml` → `$skill-creator`
- その他のPlugin/API/Web/docs-linked実装 → `$astralrecord-code`

## 必須ワークフロー

1. `$astralrecord-git-worktree-develop` のPrepareで、local `develop`からtask branch / worktreeを作る。
2. 対象workerをそのworktree内で一度に一人のwriterとして実行し、最小の対象資料だけを読んで検証する。
3. 変更種別に対応するreview/fixを実行する。
   - 設計書だけ（`00_docs/40_Database設計書`以外） → `$astralrecord-docs-review` / `$astralrecord-docs-fix`
   - code、configuration、workspace skill/tool、filebase、resourcepack、混在 → `$astralrecord-code-review` / `$astralrecord-code-fix`
4. 並列または遅延mergeなら、品質ゲート後に対象ファイルだけをcommitし、worktreeを保持してfinalize待ちにする。単独のend-to-end依頼ならfinalizeする。
5. Finalizeは `$astralrecord-git-worktree-develop` に任せる。rebase後、Plugin成果物を変更した場合だけ `$astralrecord-plugin-version` を実行する。
6. Prepare、品質ゲート、Finalizeが停止した場合はworktreeを保持し、管理ファイルを更新して停止理由を報告する。

## 条件付きで省略できる工程

- 差分なし: worktree、worker、build、review、commit、mergeをすべて省略。
- typo、コメント、表示metadataなどのLight gate: full build、Round 2、specialistを省略可。詳細は [quality-gate.md](references/quality-gate.md)。
- API/Web/docs/filebase/resourcepack/skillのみ: Plugin版番号を省略。
- Pluginのtest source、POM、許可されたdesign input、test-policy pathに変更がない: test traceability検証を省略。
- 対象外プロジェクトのguide、reference、test policy、生成物確認は省略。

## 省略してはいけないもの

- 差分を作るtaskの専用worktreeと、一人のwriterによる編集分離。
- scoped diff確認、commit前check、必要なreview、rebase後の競合・影響確認。
- `40_filebase` のrebase後YAML、ID重複、変更参照の検証。
- Plugin成果物を変更したfinalizeでの版番号更新。
- 処理終了時の `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` 更新。

## サブエージェント方針

- 利用可能なら実装者とRound 1 reviewerを分ける。
- Standard gateのRound 2 reviewerはfixerと分ける。
- trivialな一ファイル変更は独立reviewer一人でよい。
- 専門家はmulti-file、複数project、security、concurrency、data integrityのときだけ追加する。

## 最終報告

日本語で、`実装結果`、`品質ゲート`、`Branch / Worktree`、`Git結果`、`Worktree管理`、`未対応事項`を簡潔に報告する。Plugin版番号は実施した場合だけ記載し、未実施なら理由を明記する。
