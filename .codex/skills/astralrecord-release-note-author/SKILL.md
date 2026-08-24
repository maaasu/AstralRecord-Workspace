---
name: astralrecord-release-note-author
description: 開始コミットと終了コミットの範囲から、AstralRecordのプレイヤー向けリリースノートMarkdownだけを作成する。リリースノート作成時に使用し、commit・push・デプロイは行わない。
---

# AstralRecord Release Note Author

開始コミットから終了コミットまでの変更をプレイヤー向けに整理し、`00_docs/70_リリースノート/` に下書きMarkdownを1件だけ作成する。

## Invocation

このスキルは、次の形式で開始・終了コミットとリリースノート版を受け取る。

```text
$astralrecord-release-note-author <開始コミット> <終了コミット> <リリースノートバージョン>
```

例:

```text
$astralrecord-release-note-author 1a2b3c4 5d6e7f8 1.10.0
```

開始・終了コミットはどちらもcommitへ解決できるSHAまたはGit refを指定する。対象範囲は開始コミットと終了コミットを両方含み、開始コミットは終了コミットの祖先でなければならない。リリースノートバージョンはPlugin版とは独立した公開用の `X.Y.Z` 形式で、先頭の `v` を付けずに指定する。

引数が不足・過剰である、コミット参照が不正である、またはリリースノートバージョンが `X.Y.Z` 形式でない場合は、ファイル作成や差分調査を行わず、必ず次だけを返す。

```text
入力形式が正しくありません。次の形式で、開始コミット、終了コミット、リリースノートバージョンを指定してください。
$astralrecord-release-note-author <開始コミット> <終了コミット> <リリースノートバージョン>
例: $astralrecord-release-note-author 1a2b3c4 5d6e7f8 1.10.0
```

入力形式が正しくても開始コミットが終了コミットの祖先でない場合は、作成を停止し、理由と上の正しい入力形式を返す。

## Scope

- 作成対象は `00_docs/70_リリースノート/` 配下のリリースノートMarkdown 1件だけ。
- commit、stage、push、branch/worktreeの作成・切替、merge、デプロイは行わない。
- 既存の未コミット差分を変更・削除・stageしない。作成先ファイルが既に存在する場合は上書きせず、別の一意なファイル名にする。
- 公開・Discord通知は行わない。作成物は必ず `status: draft` と `notifyDiscord: false` にする。

## Workflow

1. `git rev-parse --verify <ref>^{commit}` で開始・終了参照をcommit SHAへ解決し、`git merge-base --is-ancestor <開始SHA> <終了SHA>` で範囲の妥当性を確認する。第3引数のリリースノートバージョンが `X.Y.Z` 形式であることも確認する。
2. 開始・終了コミットを含むコミット一覧を時系列順で取得する。開始コミットがrootの場合も漏らさない。各コミットは短縮SHAとsubjectを記録する。
3. `git diff`、コミット本文、必要最小限の変更ファイルを確認し、実際に利用者へ影響する追加・変更・修正だけを抽出する。内部リファクタリング、テスト、CI、依存更新だけの変更は、利用者影響がない限り本文へ書かない。
4. `00_docs/70_リリースノート/README.md` を読み、front matterと公開文面の規約に従う。第3引数のリリースノートバージョンを `version` とファイル名へ使い、Plugin版を参照・推測しない。
5. 終了コミットの日時を日本標準時へ換算し、ファイル名を `YYYY-MM-DD-v<version>.md` とする。既存ファイルと衝突する場合は、終了コミットの短縮SHAを末尾に加えて一意にする。`slug` は小文字英数字とハイフンのみで、同名の既存slugと重複させない。
6. プレイヤーに分かる自然な日本語で、タイトル、一覧用summary、本文を作成する。変更が利用者へ影響しない場合も、技術詳細を公開せず、確認・修正内容を過剰に断定しない簡潔な下書きにする。
7. 次のfront matterをすべて含むMDを作成する。`publishedAt` には終了コミットのJST日時を `+09:00` 付きISO 8601形式で入れる。

```yaml
---
slug: <一意なslug>
version: <リリースノートバージョン>
title: <プレイヤー向けタイトル>
summary: <短い概要>
publishedAt: <終了コミット日時のJST>
status: draft
notifyDiscord: false
---
```

8. 作成後にMDを再読込して、front matterの必須7項目、`status: draft`、`notifyDiscord: false`、slug形式、作成先の一意性を確認し、`git diff --check -- <作成ファイル>` を実行する。検証に失敗した場合は公開・commitを行わず、問題を報告する。

## Required result

成功時は、作成したMDの絶対パスと、作成したリリースノートMDだけを後でコミットするための**推奨コミットメッセージ**を必ず出力する。リリースノートMDの追加はドキュメント変更なので、`feat` ではなく `docs` を使う。

```text
作成したリリースノート: <absolute path>
推奨コミットメッセージ:
docs: リリースノート v<リリースノートバージョン> を追加
```

内容の要約を添えてよいが、commit・push・デプロイを実行したように示してはならない。
