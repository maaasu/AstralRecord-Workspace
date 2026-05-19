# Plugin Design Docs Reference

Use this reference for paths under `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書`.

## Required Context

Before reviewing a feature, read:

- `00_docs/10_プラグイン設計書/README.md`
- the target feature README, such as `feature/01-user/01_README.md`
- `0-概要`
- `1-モデル定義`
- `2-ユースケース`
- `3-メソッド仕様` files relevant to the reviewed behavior
- `4-統合フロー`
- `5-例外・ログ・運用`
- `9-未決事項` when present

For cross-feature dependencies, follow Wiki links such as `[[03_1.00-モデル定義]].プレイヤーセッション` to the referenced design doc. Do not inspect the implementation path listed in feature README.

## Structure Rules To Check

Use the root design README as the source of truth. Current expected structure:

- Feature directory: `2-digit-number-feature-name`, for example `01-user`.
- Feature README: `<feature-number>_README.md`.
- Category directories:
  - `0-概要`
  - `1-モデル定義`
  - `2-ユースケース`
  - `3-メソッド仕様`
  - `4-統合フロー`
  - `5-例外・ログ・運用`
  - `9-未決事項` when needed
- Markdown file name: `<feature-number>_<category-number>.<detail-number>-<name>.md`.
- Do not use `[` or `]` in file names.
- Prefer Obsidian Wiki links. Cross-doc references should be `[[file-name]].logical-name` and should not include paths.
- If a directory contains only one file, do not require an index file.

Feature README must contain:

- `対象実装パス`
- `ドキュメント一覧（推奨順）`
- `依存 feature`
- `更新ルール（変更時に必ず更新する章）`

## Method Spec Rules To Check

For docs under `3-メソッド仕様`:

- Logical names should be Japanese noun phrases, not sentence-style descriptions.
- Each method spec should include `クラス名` and `物理名`.
- Event specs should also include `イベント物理名`.
- Cross-file method references should use `[[file-name]].論理名`.
- Model references should prefer Japanese logical model names in body text.
- Process contents must include concrete judgment conditions, retrieved items, delegation targets, and failure behavior when relevant.
- Do not split validation that belongs to one process step into unrelated numbered steps.
- Logs/messages must include message content, not only log IDs.
- Logs/messages should be written directly below the process step that emits them, using the documented table format.
- Do not create an isolated `ログ/メッセージ:` heading.

## Design Review Focus

Prioritize design-level issues:

- Does the flow match the method specs and the feature overview?
- Are model fields sufficient for the stated use cases and lifecycle?
- Are responsibilities divided cleanly between event, command, service, repository, cache/session, task, adapter/listener, and operation docs?
- Are cross-feature calls explicit enough to know ownership and dependency direction?
- Are failure paths, null/not-found behavior, retries, logging, player-facing messages, and operational response documented where needed?
- Are state transitions clear for login/logout, cache/session, save timing, cooldowns, buffs/status effects, item ownership, loot grants, and other gameplay lifecycles?
- Are unresolved decisions captured in `9-未決事項` instead of hidden in body text?

## When Intent Is Missing

Gather intent from the overview, use cases, flow diagrams, unresolved issues, and related feature docs. If the intended behavior still cannot be determined, report it as `未確認/質問` with the exact decision needed, for example:

- "この処理はリトライする設計か、失敗を確定させる設計か"
- "通常プレイヤー以外のアカウントモードで GUI 反映を抑止する理由"
- "キャッシュがない場合に WARN として扱うか、正常系として扱うか"
