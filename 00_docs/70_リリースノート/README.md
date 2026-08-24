# リリースノート

このディレクトリがリリースノートの正本です。Web の公開ページは、ここにある Markdown をビルド時に `release-notes/` として同梱し、公開済みのノートを自動表示します。

## 追加手順

1. `YYYY-MM-DD-v<version>.md` を作成する。
2. front matter の `status` を `draft` のまま内容を確認する。
3. 公開時に `status: published`、`publishedAt`、`notifyDiscord: true` を設定する。
4. Web/API をデプロイする。Web 起動時に API へ登録され、API の Outbox が Discord へ URL を送信する。

`slug` は小文字英数字とハイフンだけを使用し、公開後に変更しないでください。下書きは Web に表示されず、Discord 通知も行われません。

`publishedAt` は日本標準時の日時を `+09:00` 付きISO 8601形式で指定します。Web上の公開日時も日本標準時で表示します。

## 公開文面ルール

リリースノートのタイトル、概要、本文はプレイヤー向けの公開情報です。利用者が確認できる変更内容や影響を、一般的な言葉で記載してください。

次のような開発・内部運用向け情報は、公開するリリースノートへ記載しません。

- リポジトリ内のパス、ファイル形式、front matter、デプロイ手順
- API、Outbox、内部の再試行方式など、システム内部の構成や処理
- Webコードの変更要否など、開発者だけが必要とする実装情報

これらの情報はリリースノート本文ではなく、READMEや対象機能の設計書へ記載します。

## front matter

```yaml
---
slug: example-release
version: 0.2.0
title: 公開タイトル
summary: 一覧に表示する短い説明
publishedAt: 2026-08-24T21:00:00+09:00
status: draft
notifyDiscord: true
---
```
