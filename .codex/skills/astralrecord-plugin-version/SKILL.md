---
name: astralrecord-plugin-version
description: AstralRecord の Minecraft プラグイン `10_plugin/AstralRecord` のバージョン番号を更新する。`pom.xml` の版番号を正本として、機能追加・不具合修正・リリース準備・開発版採番のたびに、一貫した版番号へ更新したい場合に使う。`plugin.yml` は `${project.version}` 参照のため直接編集せず、開発版を細かく管理したいときや、実装後にコミット前の版番号を確定したいときに使う。
---

# AstralRecord Plugin Version

## Core Rule

Treat `E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml` as the only source of truth for the plugin version. Do not edit `src/main/resources/plugin.yml` for versioning because it already resolves `version: ${project.version}` from Maven filtering.

Use a SemVer-based scheme that stays stable before commit:

- Release: `MAJOR.MINOR.PATCH`
- Development build: `MAJOR.MINOR.PATCH-dev.YYYYMMDD.N`
- Pre-release candidates when explicitly requested: `MAJOR.MINOR.PATCH-alpha.N`, `...-beta.N`, `...-rc.N`

Do not embed the future commit hash into `project.version` in the default workflow. That ordering is fragile because the version must be written before commit, while the final commit hash exists only after commit. If traceability is needed, report the resulting commit hash separately after the commit step instead of baking it into the plugin version.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\PLUGIN_GUIDE.md`.
3. Read `E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml`.
4. Confirm that `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\resources\plugin.yml` still uses `${project.version}`.

Stop and ask only if the target is not the plugin project or if the user explicitly wants a non-standard version string.

## Version Selection Rule

Choose the base bump from the implemented change:

- `major`: incompatible command/config/data contract change, or behavior that requires coordinated manual migration
- `minor`: backward-compatible feature addition or meaningful new capability
- `patch`: bug fix, tuning, small internal change, docs-aligned behavior fix
- `none`: only when the user explicitly says to keep the same core version and refresh only the development suffix

Choose the version form:

- If the user asks for a release version, write `MAJOR.MINOR.PATCH`.
- If the user asks for a development version, or the request is part of an implementation workflow before commit, write `MAJOR.MINOR.PATCH-dev.YYYYMMDD.N`.
- If the user explicitly asks for staged release testing, use `alpha`, `beta`, or `rc`.

Default assumption when the request is ambiguous: use a development version and bump `patch`.

## Workflow

1. Inspect the current plugin version in `pom.xml`.
2. Infer or confirm the bump level from the implementation scope.
3. Run the bundled updater:

```text
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-plugin-version\scripts\update_plugin_version.py --pom E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml --kind dev --bump patch
```

4. Re-read `pom.xml` and verify the written version string.
5. Confirm that `plugin.yml` still uses `${project.version}` and therefore needs no direct edit.
6. Report the old version, new version, bump reason, and whether the change is intended as dev or release.

Use explicit overrides when needed:

```text
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-plugin-version\scripts\update_plugin_version.py --pom E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml --set-version 1.3.0-rc.1
```

## Decision Notes

- If the current version is legacy Maven style such as `1.0-SNAPSHOT`, first treat it as SemVer core `1.0.0`, then apply the requested bump and suffix. Example: `--bump none` gives `1.0.0-dev.YYYYMMDD.1`, while the default `--bump patch` gives `1.0.1-dev.YYYYMMDD.1`.
- Do not decrement or rewrite unrelated metadata.
- Do not edit artifactId, groupId, plugin name, or Minecraft `api-version`.
- If the requested change touches only docs or non-plugin projects, do not use this skill.

## Report Format

Write the result in Japanese.

```markdown
## バージョン更新結果
- 旧バージョン: `<old>`
- 新バージョン: `<new>`
- 採番種別: `dev` / `release` / `alpha` / `beta` / `rc`
- 変更理由: <major/minor/patch/none の判断理由>

## 変更ファイル
- `10_plugin/AstralRecord/pom.xml`: `<version>` を更新
- `10_plugin/AstralRecord/src/main/resources/plugin.yml`: 変更なし（`${project.version}` 参照のため）

## 検証
- `pom.xml` 再読込: 成功 / 失敗

## 補足
- コミットハッシュはプラグイン版番号に埋め込まず、必要ならコミット結果として別報告
```
