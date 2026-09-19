---
name: astralrecord-plugin-version
description: AstralRecord の Minecraft プラグイン `10_plugin/AstralRecord` のバージョン番号を更新する。`pom.xml` の版番号を正本として、機能追加・不具合修正・リリース準備・開発版採番のたびに一貫した版番号へ更新したい場合に使う。並列作業では、最新版のローカル develop へリベース済みの作業ツリーで統合直前にだけ実行する。
---

# AstralRecord プラグイン版番号

## 基本ルール

プラグイン版番号の正本は `E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml` だけとする。`src/main/resources/plugin.yml` は Maven 絞り込みによって `version: ${project.version}` を解決するため、版番号更新のために編集しない。

既定の作業ブランチ作業手順では、作業ブランチを最新のローカル `develop` にリベースした後、最終マージまたはリリースコミットの直前にだけこのスキルを実行する。同じ古い基点コミットを共有する複数の並列作業ツリー内で、`pom.xml` を先に引き上げしない。

SemVer ベースの方式を使う。

- 通常ビルドと開発ビルド: `MAJOR.MINOR.PATCH`
- 明示的に要求されたプレリリース候補: `MAJOR.MINOR.PATCH-alpha.N`、`...-beta.N`、`...-rc.N`

既定作業手順では、将来のコミットハッシュを `project.version` に埋め込まない。版番号はコミット前に書く必要がある一方、最終コミットハッシュはコミット後にだけ存在するため、その順序は壊れやすい。追跡性が必要ならプラグイン版番号に埋め込まず、コミット後に得られたコミットハッシュを別途報告する。

## 必須コンテキスト

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\PLUGIN_GUIDE.md` を読む。
3. `E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml` を読む。
4. `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\resources\plugin.yml` が引き続き `${project.version}` を使っていることを確認する。
5. 現在の作業ツリーが最新のローカル `develop` にすでにリベース済みであること、または依頼が単独ののリリース/版番号管理作業であることを確認する。

対象がプラグインプロジェクトではない場合、またはユーザーが標準外の版番号文字列を明示的に求めた場合だけ停止して質問する。

## 版番号選択ルール

実装した変更から基本引き上げを選ぶ。

- `major`: 互換性のないコマンド/設定/データ契約の変更、または協調した手動移行が必要な挙動変更。
- `minor`: 後方互換のある機能追加、または意味のある新しい機能。
- `patch`: 不具合修正、調整、小規模な内部変更、設計書と整合させる挙動修正。
- `none`: ユーザーが中核版番号を同じに保ち、開発接尾辞だけを更新すると明示した場合だけ。

版番号形式を選ぶ。

- ユーザーがリリースまたは開発版番号を求めた場合、またはコミット前の実装作業手順の一部である場合は `MAJOR.MINOR.PATCH` を書く。
- 通常の作業作業手順の一部である場合は、実装コミットとリベースがすでに完了しており、マージ前の最後の変更可能な手順であるとみなす。
- ユーザーがステージ済みリリーステストを明示的に求めた場合は、連番付きの `alpha`、`beta`、`rc` を使う。

依頼が曖昧な場合の既定は、通常版番号の `patch` 引き上げとする。

## 手順

1. `pom.xml` の現在のプラグイン版番号を確認する。
2. この作業ツリーがリベース済みの完了処理対象であること、または作業が意図的な単独のリリース/版番号更新操作であることを確認する。
3. 実装範囲から引き上げレベルを判断または確認する。
4. 同梱更新ツールを実行する。

```text
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-plugin-version\scripts\update_plugin_version.py --pom E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml --kind release --bump patch
```

5. `pom.xml` を再読し、書き込まれた版番号文字列を確認する。
6. `plugin.yml` が引き続き `${project.version}` を使っており、直接編集が不要であることを確認する。
7. 旧版、新版、引き上げ理由、開発用かリリース用かを報告する。

必要な場合は明示的な上書きを使う。

```text
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-plugin-version\scripts\update_plugin_version.py --pom E:\AstralRecord-Workspace\10_plugin\AstralRecord\pom.xml --set-version 1.3.0-rc.1
```

## 判断メモ

- 開発または Maven スナップショット接尾辞を持つ旧方式の版番号は、次の通常版番号を書くとき `MAJOR.MINOR.PATCH` の中核に正規化する。例: `1.0-SNAPSHOT` または `1.0.0-dev.12` に `--bump none` を使うと `1.0.0`、既定の `--bump patch` では `1.0.1` になる。
- プレリリースの連番は `1` から開始し、現在の一致する `alpha`、`beta`、`rc` 版番号から増加させる。
- 無関係なメタデータを減らしたり書き換えたりしない。
- artifactId、groupId、プラグイン名前、Minecraft `api-version` を編集しない。
- 依頼された変更が設計書またはプラグイン以外のプロジェクトだけに触れる場合は、このスキルを使わない。
- 並列作業作業手順では、作業ブランチを最新のローカル `develop` にリベースする前にこのスキルを実行しない。

## 報告形式

結果は日本語で記載する。

```markdown
## バージョン更新結果
- 旧バージョン: `<old>`
- 新バージョン: `<new>`
- 採番種別: `release` / `alpha` / `beta` / `rc`
- 変更理由: <major/minor/パッチ/none の判断理由>

## 変更ファイル
- `10_plugin/AstralRecord/pom.xml`: `<version>` を更新
- `10_plugin/AstralRecord/src/main/resources/plugin.yml`: 変更なし（`${project.version}` 参照のため）

## 検証
- `pom.xml` 再読込: 成功 / 失敗
- 実行タイミング: rebased 完了処理 / 単独のリリース作業

## 補足
- コミットハッシュはプラグイン版番号に埋め込まず、必要ならコミット結果として別報告
```
