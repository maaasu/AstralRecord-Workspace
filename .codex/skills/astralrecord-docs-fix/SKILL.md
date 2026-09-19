---
name: astralrecord-docs-fix
description: astralrecord-docs-review のレビュー結果に基づき AstralRecord 設計書を修正する。設計書レビュー指摘の反映、AR-DOC 指摘 ID からの Markdown 更新、00_docs 配下の指摘解決を、ソースコードを変更せずに行いたい場合に使う。
---

# AstralRecord 設計書修正

## 基本ルール

設計書だけを修正する。ソースコード、実装ファイル、database schema file、生成 asset、runtime output、deployment file は開いたり編集したりしない。設計書に書かれた実装パスは範囲を示すラベルとしてだけ扱う。

何を変更するかはレビュー結果を正とする。不足している設計意図を作らない。指摘が `要確認` または `設計判断待ち` の場合は、依頼で不足する判断が明示されない限り未解決のまま残す。

保存済みレビュー記録を解析または更新する前に、`<task-root>\.codex\skills\_shared\review-record-format.md` を最後まで読む。正規スキーマと更新スクリプトは必須とする。

## 入力

次のいずれかを受け付ける。

- docs 対象パスと、レビュー結果のパスまたは貼り付けたレビュー本文。
- `AR-DOC-001` のように、会話中にすでに示されたレビュー指摘 ID を参照する依頼。

レビュー結果または指摘の詳細がない場合は、編集前にレビュー結果を求める。

## 手順

1. docs 対象パスとレビュー元を特定する。
   - `git rev-parse --show-toplevel` で `<task-root>` を解決する。
   - docs またはレビュー記録を編集する前に、`develop` ではない専用 task branch/worktree を必須とする。ない場合は `develop` に書き込まず、統合 worktree 手順を使う。
   - 提供された記録パスが `E:\AstralRecord-Workspace` 配下なら、`<task-root>` 配下の同じ相対パスに読み替え、その worktree のコピーだけを更新する。
   - 提供された記録が別の task worktree 内にある場合は停止し、その記録の worktree を再利用する統合 review-fix 入口を要求する。修正と正規記録を branch 間で分割しない。
2. `astralrecord-docs-review` の報告形式でレビュー結果を解析する。
   - `AR-DOC-*` finding IDs.
   - `対象`, `関連箇所`, `修正方針`, `修正対象候補`, `修正可否`, `確信度`, and `修正状態`.
   - `修正スキル入力サマリ` がある場合はそれも読む。
3. 修正対象の指摘を選ぶ。
   - 既定では `修正可否: 自動修正可` の指摘をすべて修正する。
   - ユーザーが特定の ID を指定した場合は、その ID だけを修正する。
   - 必要な判断がユーザーから与えられない限り、`要確認` または `設計判断待ち` の指摘は修正しない。
4. 整合した最小限の編集に必要な対象設計書と周辺設計書を読む。`修正対象候補` に挙がったファイルを優先し、`複数` と書かれている場合は、編集前に参照された各対象を読む。
5. ローカルの構成、見出し、用語、Wiki link 形式、table 形式を保ちながら、指摘を解消する最小限の Markdown/設計変更を適用する。
6. 編集後、変更箇所を再読し、各修正対象の指摘が解消されていることを確認する。
7. レビュー元が `<task-root>\00_docs\99_資料\レビュー結果` 配下の保存済み記録なら、修正済み状態と派生メタデータだけを次のコマンドで更新する。

```powershell
python <task-root>\.codex\skills\_shared\scripts\update_review_record.py <record-path> --fixed <AR-DOC-IDs>
```

   - 記録の名前変更、メタデータの書き換え、指摘フィールドの削除、指摘本文の要約、指摘順の変更、ID の振り直しを手動で行わない。
   - 元の timestamp、対象パス、`docs-review` skill 名を保持する。
   - 回答が提示済みまたは明確に確認できる場合だけ、質問ごとに一度 `--resolve-question '<Q-DOC-ID>=<confirmed answer>'` を追加する。それ以外は省略し、質問を `未確認` のまま残す。
   - 返されたパスを `validate_review_record.py` で再検証し、失敗した場合は記録の更新完了を報告しない。
8. Plugin 設計書では、次を実行する。

```powershell
python <task-root>\.codex\skills\astralrecord-docs-review\scripts\docs_structure_audit.py <absolute-docs-path>
```

この audit を形式確認として使う。script が無関係な既存問題を報告した場合は、編集範囲を広げず別項目として列挙する。
9. 変更した設計書が `00_docs/10_Plugin設計書` 配下にある場合は、この skill が Plugin source を開いたり解釈したりしない場合でも、`<task-root>` から次の black-box consistency gate も実行する。docs-only の見出し/path 編集後に既存の恒久テスト参照が解決できるかを確認する。

```powershell
python <task-root>\.codex\skills\astralrecord-plugin-test\scripts\validate_test_traceability.py --repo-root <task-root>
```

`mvn verify` で代替しない。その Plugin shade output はメイン workspace の配布先を対象とする。

## 編集時の制約

- 変更は、指摘が明示的に別の場所を指していない限り、依頼された対象の設計書に限定する。
- feature docs ですでに使われている日本語の用語を維持する。
- 複数ファイルへ説明を重複させず、正本の設計書を修正することを優先する。
- レビュー結果またはユーザー指示が未決の設計判断を記録するよう求めている場合は、判断せず category `9` に残す。受け入れ済みだが未実装の仕様は category `8` に残し、2つの状態を混在させない。
- docs またはユーザーが示していない実装詳細を追加しない。
- レビューで確認が必要とされた挙動について、1つを選んで曖昧さを取り除かない。
- `未確認/質問`（`Q-DOC-*`）は、レビュー結果、ユーザーの指定、または許可された docs context から明確に確認できる場合だけ解決する。それ以外は未解決のまま報告に残す。

## 報告形式

結果は日本語で記載する。

```markdown
## 修正結果
- `AR-DOC-001`: 修正済み - <何を変えたか>
- `AR-DOC-002`: 未対応（要確認） - <必要な確認>

## 変更ファイル
- `<path>`: <変更概要>

## 未対応
- `AR-DOC-002`: <理由>

## 検証
- 変更箇所の再確認: 実施
- docs_structure_audit.py: 実行 / 未実行（理由: ...）
- validate_test_traceability.py: 実行 / 未実行（理由: Plugin設計書以外）
- ソースコード参照: していません
```
