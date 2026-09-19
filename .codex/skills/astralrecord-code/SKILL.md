---
name: astralrecord-code
description: AstralRecord モノレポ全体の実装 worker。準備済み task worktree の中で、設計書パスを入力にした実装、プラグイン/API/Web/DB/filebase/resourcepack の挙動変更、アイテム ID 変更などを行い、必要に応じて関連設計書を同期する。ユーザーが通常の実装修正を依頼し、worktree 作成や commit / develop 反映も必要になり得る場合は、直接この worker ではなく統合入口 `$astralrecord-code-version-commit-develop` を優先する。
---

# AstralRecord コード実装

## 基本ルール

対象プロジェクトを特定し、文書化されたルールを読んでからコードまたは実装隣接データを実装する。変更によって仕様の記載が明確化・限定・変更される場合は、実装した挙動を関連する設計書へ反映する。ルートガイド、プロジェクトの `README.md`、プロジェクトの `AGENTS.md`、または skill reference にルールがある場合、ソースだけから運用ルールを推測しない。

この skill は次の2種類の入力を扱う。

- 設計駆動実装: 設計書パス、仕様パス、または docs feature パスが指定される。設計書を実装の正本として読み、整合した最小限のコード変更を実装する。
- 個別実装指示: ユーザーが `表示アイテムを apple から iron_ingot に変更` のような直接の変更を指定する。影響するプロジェクトとコード/データを特定し、ローカルのコーディングルールに従って実装する。

## 必須コンテキスト

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. 明示された path、技術用語、影響ファイルから対象 project を特定する。
3. 編集前に対象 project の rule file を読む。
   - Minecraft plugin のコードでは `PLUGIN_GUIDE.md` と `references/plugin-code.md`。
   - REST API のコードでは `API_GUIDE.md` と `references/api-code.md`。
   - Razor Pages の Web コードでは、ルート `README.md` の「AstralRecord Web」節。
   - SQL Server の schema または table の設計書では `00_docs/40_Database設計書/README.md`。
   - ファイルベースのマスターデータでは、ルート `README.md` の「AstralRecord Filebase」節。
   - Resource Pack の asset または JSON では、ルート `README.md` の「AstralRecord Resource Pack」節。
4. 依頼が project 境界をまたぐ場合は、project ごとに作業を分け、それぞれの rule を読む。

対象 project を特定できない場合は停止し、ルート `AGENTS.md` の project selection question を尋ねる。

## 手順

1. 依頼を分類する。
   - `00_docs/` 配下の設計パス → `references/design-driven-implementation.md` を使う。
   - `10_plugin/AstralRecord` 配下の Plugin 実装 → `PLUGIN_GUIDE.md` と `references/plugin-code.md` を使う。
   - `10_plugin/AstralRecord` 配下の Plugin test / MockBukkit / dev-server の足場作り → 主目的が機能挙動ではなく検証基盤なら `$astralrecord-plugin-test` を優先する。
   - API 実装 → `API_GUIDE.md` と `references/api-code.md` を使い、その後この skill の一般手順を適用する。
   - Web 実装 → ルート `README.md` の「AstralRecord Web」節を使い、その後この skill の一般手順を適用する。
   - Database、filebase、resourcepack の変更 → `00_docs/40_Database設計書/README.md` またはルート `README.md` の「AstralRecord Filebase」/「AstralRecord Resource Pack」節を使う。プロジェクトルールが別途定めない限り、生成物と runtime 出力は対象外とする。
2. 最小限のコンテキストを構築する。
   - 設計駆動作業では、指定された設計書、feature 概要、リンクされた contract docs、planned specification、未決事項を読む。
   - 個別指示では、指定された symbol、item ID、route、message、table、resource key を検索する。
   - Plugin の log/message/DB/filebase 依存関係では、`references/plugin-code.md` の専門ルールを使う。
3. 編集範囲を計画する。
   - 対象となる project と file group を明示する。
   - ユーザーが cross-project 実装を要求していない限り、docs、source、database、filebase、resourcepack の論点を分離する。
4. 既存のローカルパターンで実装する。
   - 周辺の言語、命名、package、layer、DI、エラー処理、テストに合わせる。
   - 新しい場当たり的な string や abstraction より、既存の enum、ID、repository、DTO、service、helper、resource の慣例を優先する。
   - 無関係なリファクタリングは対象外とする。
5. 設計書を同期する。
   - 設計駆動作業では、実装後の挙動、名前、ID、route、command、table、file、message、未決事項を正確に記載するよう正本の設計書を更新する。
   - 個別実装では、安定した docs path、feature 名、ID、route、table、またはプロジェクトルールから関連設計書を合理的に特定できる場合は探し、実装が記載内容を変更するなら更新する。
   - 設計書の編集は狭く事実に基づくものとする。無関係な節を書き換えず、将来の挙動を作らず、ユーザー入力なしに未決事項を確定しない。
   - 合理的な検索で関連設計書を特定できない場合は、新しい推測的な設計書を作らず、その事実を報告する。
6. 検証する。
   - 変更したプロジェクトに対して、意味のある最小限の test または build check を実行する。
   - feature/behavior 変更、実行可能 script、schema/data contract、workspace skill logic、複数ファイル作業、security/concurrency/data-integrity 変更では、標準エラーを含む完全な検証出力を取得し、終了コードだけでなく警告も確認する。作業が原因の警告は解消して同じ check を再実行する。残った警告は既存（可能なら現在の local `develop` で確認）か外部/toolchain 起因かを分類し、command、警告概要、分類、理由を報告する。ユーザーが明示的に先送りを承認しない限り、新規の未説明警告または作業起因の警告が残る変更を引き渡さない。
   - Plugin の source/resource 変更では、build/test に渡す前に必ず `python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <task-worktree>` を実行する。ID/property のずれ、重複 key、log placeholder の不一致、直接の logger/message 呼び出し、command message helper に渡す文字列リテラルを解消し、再利用した ID の property 文言が実際の操作を表すことも手動確認する。
   - Plugin 作業では、`references/plugin-code.md` の「Plugin Test Traceability Gate」に対して task 全体の diff を確認する。Plugin POM を含む trigger path が変わった場合は、`src/test` が変わらない場合や docs-only/test-policy-only の場合でも traceability validator を実行する。この gate に `mvn verify` を使わない。
   - full build が高コストまたはブロックされる場合は、対象を絞った compile/test/lint check を実行し、未実行のものを報告する。
   - 最終報告前に、変更した source と設計書の断片を再読してルールに適合していることを確認する。

## 設計駆動実装

ユーザーが設計書パスを指定した場合、その設計書を実装の入力とし、完了した実装と整合させる。既定では設計書を広範囲に書き換えず、実装によって記載された挙動、contract、名前、状態が変わった部分だけを更新する。

- 必要な挙動、data contract、lifecycle/state rule、command/route、message/log、エラー挙動、未決事項を抽出する。
- 設計書が未決としている挙動は、ユーザーから不足する判断が与えられない限り実装しない。
- 設計書と現在のコードが矛盾する場合、新規実装では明示された設計を優先する。ただしリスクが生じる不一致は報告する。
- 実装によって設計書の不足が明らかになった場合は、関連設計書に必要最小限の注記または状態更新を加える。不足がより広い設計修正を要求する場合は、この skill をコーディングに集中させ、残る設計書作業を別途記載する。

詳細な checklist は `references/design-driven-implementation.md` を参照する。

## Plugin 固有ルール

`10_plugin/AstralRecord` では、コード変更前に必ず `PLUGIN_GUIDE.md` を読み、その後 `references/plugin-code.md` を使う。旧 `/code` prompt と旧 helper prompt は、その reference に移行済みとして扱う。

## 報告形式

結果は日本語で記載する。

```markdown
## 実装結果
- <変更概要>

## 変更ファイル
- `<path>`: <変更内容>

## 設計書反映
- `<path>`: <反映内容> / 未反映（理由）

## 検証
- `<command>`: 成功 / 失敗 / 未実行（理由）

## 残事項
- なし / <未対応・要確認事項>
```
