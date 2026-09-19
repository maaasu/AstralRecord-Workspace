---
name: astralrecord-docs-review
description: AstralRecord の設計書をソースコードを読まずにレビューし、指摘がある場合のみ固定書式のレビュー記録を専用 task worktree 内へ安全に保存する。設計整合性、不適切なロジック、意図不足、未決事項、文書間矛盾、命名・フォーマットルールの確認や docs-only 変更後の独立レビューで使う。可能な場合は読み取り専用サブエージェントを活用する。
---

# AstralRecord 設計書レビュー

## 基本ルール

設計書だけをレビューする。ソースコード、実装ファイル、database schema file、生成 asset、runtime output は開いたり、そこから推測したりしない。設計書に書かれた実装パスは範囲を示すラベルとしてだけ扱う。指摘が1件以上ある場合だけ正規レビュー記録を作成する。指摘がない場合はレビュー記録を作成せず、作成しなかったことと未解決の質問をレビュー結果に記載する。

判断が設計者の意図に依存する場合は、まず docs から意図を集める。対象は root README、feature 概要、use case、model 定義、flow、operation note、planned specification、未決事項、関連 feature docs とする。それでも意図が不明な場合は、無理に欠陥とせず質問または仮定として報告する。

選択した repository root を解決し、レビュー前に `<repo-root>\.codex\skills\_shared\review-record-format.md` を最後まで読む。その保存場所、ファイル名、本文スキーマ、状態、検証ルールは必須とする。

## Git 事前確認

レビュー記録を作成する前に、次を完了する。

1. `git rev-parse --show-toplevel` で選択した checkout を解決し、`git status --short --branch` を確認する。
2. すでに `develop` ではない専用 task worktree 内にいる場合は、その root を `<task-root>` とする。
3. 選択した checkout がメインの `develop` workspace の場合、未コミット変更がレビュー対象と重なるか確認する。重なる場合は書き込み前に停止し、その変更を task worktree へ移すよう要求する。dirty diff を含むかのように stale HEAD のコピーをレビューしてはならない。重ならない場合は `$astralrecord-git-worktree-develop` を Prepare mode で起動して `codex/review-<slug>` を作成し、対象パスをその worktree に読み替えて、新しい root を `<task-root>` とする。
4. 指摘により記録が必要な場合は `<task-root>\00_docs\99_資料\レビュー結果` にだけ保存する。task worktree からメイン workspace のリテラルパスへ書き込まない。
5. 既存 task worktree でレビューだけの依頼が終わり、記録を作成した場合は `$astralrecord-commit-current-diff` を起動し、検証済み記録だけを commit する。指摘がなく記録を作成しなかった場合は、記録の commit もない。要求されない限り、既存の実装 worktree を finalize しない。
6. この skill がレビュー専用 worktree を作成し、記録を作成した場合は、検証直後に `$astralrecord-git-worktree-develop` を Finalize mode で起動する。記録の stage と commit は Finalize に任せる。記録を作成しなかった場合は不要だったと報告する。blocked の場合は branch/worktree を保持して報告する。先に commit してから Finalize を呼んだり、`develop` への書き込みに戻ったりしない。

## 手順

1. Git 事前確認を完了し、ファイルへ書き込む前に `<task-root>` を確定する。
2. 読み替えた絶対パスから対象の設計領域を特定する。
   - `00_docs/10_Plugin設計書`: `references/plugin-design-docs.md` を読む。
   - 将来の API/Web 設計書: この一般手順を使い、`references/api-design-docs.md` や `references/web-design-docs.md` のような追加 reference を探す。領域固有の reference がなければ、一般的な設計品質と文書化されたローカルルールだけをレビューする。
3. 判断前に文書化されたルールを読む。docs root README、対象 feature 概要、対象 docs tree 内のローカルルールファイルを確認する。
4. `00_docs/10_Plugin設計書` をレビューする場合だけ、`<task-root>\.codex\skills\astralrecord-docs-review\scripts\docs_structure_audit.py <absolute-docs-path>` を実行する。その出力は形式・構造の指摘の証拠として使い、レビュー全体の代用にはしない。他の docs 領域では、対応する領域 audit script が追加されていない限り、確認範囲に `未実行（理由: docs_structure_audit.py は 10_Plugin設計書 専用）` と記載する。
5. feature の意図と文書間 contract を理解するために必要な最小限の関連設計書を読む。用語、model、method、flow、dependency、未決事項を定義する Wiki link 先の docs は追って読む。
6. 設計上の欠陥をレビューする。
   - overview、model、use case、method spec、integration flow、operation/logging、未決事項の間の矛盾。
   - 仕様不足、記載どおりに実装できない、運用上安全でない、または責務記載と一致しない logic。
   - 設計が示唆する precondition、failure behavior、所有境界、dependency、data lifecycle、state transition、idempotency、concurrency、rollback、observability の不足。
   - 文書化された docs rule に対する形式・命名違反。
7. 「指摘」と「質問」を分離する。現在の docs が定義を要求していない設計者の意図不足を欠陥と呼ばない。
8. 範囲が非自明で、sub-agent が利用できる場合は少なくとも1回の独立した読み取り専用 pass を委譲する。cross-feature、運用リスク、data lifecycle の作業では、異なる観点を持つ2人目の specialist を使う。agent には期待する指摘ではなく、対象 docs とローカルルールを渡す。調整役が証拠を重複排除し、正規記録の唯一の writer となる。
9. レビュー中に、レビュー対象の設計書から回答できる質問は解決する。許可されたレビュー資料から確認できない判断または事実だけを `## 未確認/質問` に残す。
10. 指摘に紐づく質問が必要な場合は `## 未確認/質問` の下に置き、`関連指摘` で指摘を参照する。
    - 指摘がない場合は記録を作成せず、記録の検証も実行しない。代わりに未解決の質問をレビュー結果へ記載する。
11. 指摘が1件以上ある場合は、共有形式で正規記録を1つだけ作成する。下記の docs-review 許可種別を使い、新しい指摘はすべて `修正状態: 未修正` で始め、見出しを追加しない。指摘がない場合は記録を作成しない。
    - Round 1 は指摘がある場合だけ記録を作成し、絶対パスを調整役へ返す。
    - Round 2 は記録がある場合にその正規記録パスを入力として受け取り、同じファイルを更新する。既存の ID/本文/timestamp/target を保持し、新しい連番の指摘だけを追加する。2つ目の記録や空の記録を作成しない。
12. 保存したファイルを `<task-root>\.codex\skills\_shared\scripts\validate_review_record.py` で検証する。通過するまで修正する。
13. Git 事前確認で定めたレビュー専用の commit/finalize 手順を完了する。統合実装 workflow では、commit/finalize の所有権を調整役に残す。

## 報告形式

レビューは日本語で記載する。記録がある場合は構成を変えずに検証済み本文を出力し、共有形式の正規本文と正確な節順を使う。指摘がない場合は記録を作成しなかったことを報告し、未解決の質問をレビュー結果に含める。重要度は `[高]`、`[中]`、`[低]`、`[情報]` のいずれかとする。

docs review で使用できる `種別` は次のとおり。

`矛盾` | `不適切なロジック` | `不足` | `未確定事項` | `形式/命名` | `運用リスク`

新しい意図を作らずに `$astralrecord-docs-fix` が設計書を編集できる場合だけ `修正可否: 自動修正可` とする。共有形式のフィールド順に `修正対象候補`、`確信度`、`修正状態` を必ず含める。`確認した範囲` には `読んだソース: なし（設計書レビューのため）` を使う。

## レビュー結果ファイル

指摘が1件以上ある場合は、`<task-root>\00_docs\99_資料\レビュー結果` 配下に Markdown 記録を1つだけ保存する。指摘がない場合は記録を保存しない。指摘のない記録を作る代わりに、未解決の質問をレビュー結果へ記載する。ファイル名、メタデータ、フィールド、空値、状態遷移、検証の正本は共有形式だけとする。`<task-root>` が別 worktree の場合、`E:\AstralRecord-Workspace` をリテラルの保存先として使わない。

## 拡張ポイント

領域固有のルールは1階層の reference file にまとめる。

- `10_Plugin設計書` 用は `references/plugin-design-docs.md`。
- 将来の API 設計書用には `references/api-design-docs.md` を追加する。
- 将来の Web 設計書用には `references/web-design-docs.md` を追加する。

新しい領域 reference を追加する場合は、path の判定方法、必須の root file、文書カテゴリ、レビュー観点、形式ルールを含める。大きな設計書を skill にコピーせず、workflow から実際の docs tree を参照させる。
