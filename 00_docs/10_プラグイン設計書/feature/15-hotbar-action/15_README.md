# 15 ホットバーアクション

## 対象実装パス

`E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\hotbaraction\`

## ドキュメント一覧

| ファイル | 内容 |
|:--|:--|
| [[15_0.00-概要]] | 機能概要・設計方針 |
| [[15_3.01-イベント]] | イベントハンドラ仕様 |

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| [[08_README\|08-inventory]] | ホットバースロット定義 |
| [[03_README\|03-player]] | AstPlayer・AstPlayerCache |
| [[04_README\|04-item]] | ItemStackFactory（AstralRecord アイテム判定） |
| [[14_README\|14-combat]] | 将来の左クリック攻撃委譲先 |

## 更新ルール

- アクション種別を追加・変更した場合は [[15_3.01-イベント]] を更新する。
- 新しい PlayerMsgId / LogId を追加した場合は [[15_0.00-概要]] の ID 一覧を更新する。
