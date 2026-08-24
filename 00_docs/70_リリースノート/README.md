# リリースノート

このディレクトリがリリースノートの正本です。Web の公開ページは、ここにある Markdown をビルド時に `release-notes/` として同梱し、公開済みのノートを自動表示します。

## 追加手順

1. `YYYY-MM-DD-v<version>.md` を作成する。
2. front matter の `status` を `draft` のまま内容を確認する。
3. 公開時に `status: published`、`publishedAt`、`notifyDiscord: true` を設定する。
4. Web/API をデプロイする。Web 起動時に API へ登録され、API の Outbox が Discord へ URL を送信する。

`slug` は小文字英数字とハイフンだけを使用し、公開後に変更しないでください。下書きは Web に表示されず、Discord 通知も行われません。

## front matter

```yaml
---
slug: example-release
version: 0.2.0
title: 公開タイトル
summary: 一覧に表示する短い説明
publishedAt: 2026-08-24T09:00:00+09:00
status: draft
notifyDiscord: true
---
```
