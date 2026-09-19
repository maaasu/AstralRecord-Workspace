---
name: astralrecord-plugin-version
description: AstralRecord の Minecraft プラグイン `10_plugin/AstralRecord` のバージョン番号を更新する。`pom.xml` の版番号を正本として、機能追加・不具合修正・リリース準備・開発版採番のたびに一貫した版番号へ更新したい場合に使う。並列 task 運用では、最新版の local develop へ rebase 済みの task worktree で finalize 直前にだけ実行する。
---

# AstralRecord Plugin 版番号

## 基本ルール

Plugin 版番号の正本は `E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml` だけとする。`src/main/resources/plugin.yml` は Maven filtering によって `version: ${project.version}` を解決するため、版番号更新のために編集しない。

既定の task-branch workflow では、task branch を最新の local `develop` に rebase した後、最終 merge または release commit の直前にだけこの skill を実行する。同じ古い base commit を共有する複数の並列 task worktree 内で、`pom.xml` を先に bump しない。

SemVer ベースの方式を使う。

- 通常 build と開発 build: `MAJOR.MINOR.PATCH`
- 明示的に要求された pre-release candidate: `MAJOR.MINOR.PATCH-alpha.N`、`...-beta.N`、`...-rc.N`

既定 workflow では、将来の commit hash を `project.version` に埋め込まない。版番号は commit 前に書く必要がある一方、最終 commit hash は commit 後にだけ存在するため、その順序は壊れやすい。追跡性が必要なら Plugin 版番号に埋め込まず、commit 後に得られた commit hash を別途報告する。

## 必須コンテキスト

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\PLUGIN_GUIDE.md` を読む。
3. `E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml` を読む。
4. `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\resources\plugin.yml` が引き続き `${project.version}` を使っていることを確認する。
5. 現在の task worktree が最新の local `develop` にすでに rebase 済みであること、または依頼が standalone の release/version-management task であることを確認する。

対象が Plugin project ではない場合、またはユーザーが non-standard version string を明示的に求めた場合だけ停止して質問する。

## 版番号選択ルール

実装した変更から基本 bump を選ぶ。

- `major`: 互換性のない command/config/data contract の変更、または協調した手動 migration が必要な挙動変更。
- `minor`: 後方互換のある feature 追加、または意味のある新しい capability。
- `patch`: bug fix、調整、小規模な内部変更、設計書と整合させる挙動修正。
- `none`: ユーザーが core version を同じに保ち、development suffix だけを更新すると明示した場合だけ。

version 形式を選ぶ。

- ユーザーが release または development version を求めた場合、または commit 前の実装 workflow の一部である場合は `MAJOR.MINOR.PATCH` を書く。
- 通常の task workflow の一部である場合は、実装 commit と rebase がすでに完了しており、merge 前の最後の変更可能な手順であるとみなす。
- ユーザーが staged release testing を明示的に求めた場合は、連番付きの `alpha`、`beta`、`rc` を使う。

依頼が曖昧な場合の既定は、通常 version の `patch` bump とする。

## 手順

1. `pom.xml` の現在の Plugin 版番号を確認する。
2. この worktree が rebase 済みの finalize 対象であること、または task が意図的な standalone release/versioning 操作であることを確認する。
3. 実装範囲から bump level を判断または確認する。
4. 同梱 updater を実行する。

```text
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-plugin-version\scripts\update_plugin_version.py --pom E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml --kind release --bump patch
```

5. `pom.xml` を再読し、書き込まれた version string を確認する。
6. `plugin.yml` が引き続き `${project.version}` を使っており、直接編集が不要であることを確認する。
7. 旧版、新版、bump 理由、dev 用か release 用かを報告する。

必要な場合は明示的な override を使う。

```text
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-plugin-version\scripts\update_plugin_version.py --pom E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml --set-version 1.3.0-rc.1
```

## 判断メモ

- development または Maven snapshot suffix を持つ legacy version は、次の通常 version を書くとき `MAJOR.MINOR.PATCH` の core に正規化する。例: `1.0-SNAPSHOT` または `1.0.0-dev.12` に `--bump none` を使うと `1.0.0`、既定の `--bump patch` では `1.0.1` になる。
- pre-release の連番は `1` から開始し、現在の一致する `alpha`、`beta`、`rc` version から増加させる。
- 無関係な metadata を減らしたり書き換えたりしない。
- artifactId、groupId、plugin name、Minecraft `api-version` を編集しない。
- 依頼された変更が docs または Plugin 以外の project だけに触れる場合は、この skill を使わない。
- 並列 task workflow では、task branch を最新の local `develop` に rebase する前にこの skill を実行しない。

## 報告形式

結果は日本語で記載する。

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
