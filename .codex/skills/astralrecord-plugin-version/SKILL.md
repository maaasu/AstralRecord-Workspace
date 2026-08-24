---
name: astralrecord-plugin-version
description: AstralRecord の Minecraft プラグイン `10_plugin/AstralRecord` のバージョン番号を更新する。`pom.xml` の版番号を正本として、機能追加・不具合修正・リリース準備・開発版採番のたびに一貫した版番号へ更新したい場合に使う。並列 task 運用では、最新版の local develop へ rebase 済みの task worktree で finalize 直前にだけ実行する。
---

# AstralRecord Plugin Version

## Core Rule

Treat `E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml` as the only source of truth for the plugin version. Do not edit `src/main/resources/plugin.yml` for versioning because it already resolves `version: ${project.version}` from Maven filtering.

In the default task-branch workflow, run this skill only after the task branch has been rebased onto the latest local `develop` and immediately before the final merge or release commit. Do not pre-bump `pom.xml` inside multiple parallel task worktrees that still share the same old base commit.

Use a SemVer-based scheme:

- Normal and development builds: `MAJOR.MINOR.PATCH`
- Pre-release candidates when explicitly requested: `MAJOR.MINOR.PATCH-alpha.N`, `...-beta.N`, `...-rc.N`

Do not embed the future commit hash into `project.version` in the default workflow. That ordering is fragile because the version must be written before commit, while the final commit hash exists only after commit. If traceability is needed, report the resulting commit hash separately after the commit step instead of baking it into the plugin version.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\PLUGIN_GUIDE.md`.
3. Read `E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml`.
4. Confirm that `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\resources\plugin.yml` still uses `${project.version}`.
5. Confirm that the current task worktree has already been rebased onto the latest local `develop`, or that the request is an explicit standalone release/version-management task.

Stop and ask only if the target is not the plugin project or if the user explicitly wants a non-standard version string.

## Version Selection Rule

Choose the base bump from the implemented change:

- `major`: incompatible command/config/data contract change, or behavior that requires coordinated manual migration
- `minor`: backward-compatible feature addition or meaningful new capability
- `patch`: bug fix, tuning, small internal change, docs-aligned behavior fix
- `none`: only when the user explicitly says to keep the same core version and refresh only the development suffix

Choose the version form:

- If the user asks for a release or development version, or the request is part of an implementation workflow before commit, write `MAJOR.MINOR.PATCH`.
- If the request is part of the normal task workflow, assume the implementation commit and rebase are already complete and this is the last mutable step before the merge.
- If the user explicitly asks for staged release testing, use `alpha`, `beta`, or `rc` with a sequence number.

Default assumption when the request is ambiguous: use a normal version and bump `patch`.

## Workflow

1. Inspect the current plugin version in `pom.xml`.
2. Confirm that this worktree is the rebased finalize target, or that the task is a deliberate standalone release/versioning operation.
3. Infer or confirm the bump level from the implementation scope.
4. Run the bundled updater:

```text
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-plugin-version\scripts\update_plugin_version.py --pom E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml --kind release --bump patch
```

5. Re-read `pom.xml` and verify the written version string.
6. Confirm that `plugin.yml` still uses `${project.version}` and therefore needs no direct edit.
7. Report the old version, new version, bump reason, and whether the change is intended as dev or release.

Use explicit overrides when needed:

```text
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-plugin-version\scripts\update_plugin_version.py --pom E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml --set-version 1.3.0-rc.1
```

## Decision Notes

- Legacy versions with development or Maven snapshot suffixes are normalized to their `MAJOR.MINOR.PATCH` core when the next normal version is written. Example: `--bump none` on `1.0-SNAPSHOT` or `1.0.0-dev.12` gives `1.0.0`, while the default `--bump patch` gives `1.0.1`.
- Pre-release sequence numbers start at `1` and increment from the current matching `alpha`, `beta`, or `rc` version.
- Do not decrement or rewrite unrelated metadata.
- Do not edit artifactId, groupId, plugin name, or Minecraft `api-version`.
- If the requested change touches only docs or non-plugin projects, do not use this skill.
- In a parallel task workflow, never run this skill before the task branch has been rebased onto the latest local `develop`.

## Report Format

Write the result in Japanese.

```markdown
## バージョン更新結果
- 旧バージョン: `<old>`
- 新バージョン: `<new>`
- 採番種別: `release` / `alpha` / `beta` / `rc`
- 変更理由: <major/minor/patch/none の判断理由>

## 変更ファイル
- `10_plugin/AstralRecord/pom.xml`: `<version>` を更新
- `10_plugin/AstralRecord/src/main/resources/plugin.yml`: 変更なし（`${project.version}` 参照のため）

## 検証
- `pom.xml` 再読込: 成功 / 失敗
- 実行タイミング: rebased finalize / standalone release task

## 補足
- コミットハッシュはプラグイン版番号に埋め込まず、必要ならコミット結果として別報告
```
