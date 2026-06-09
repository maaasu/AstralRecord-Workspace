# 15 ホットバーアクション（移行済み）

この feature は現役の独立実装を持たない。旧ホットバーアクション設計は、攻撃起動を `item`、ホットバー操作を `inventory`、built-in skill 実行とアクションリングを `skill` へ移管済みとして扱う。

## 対象実装パス

- なし（移行済み）
- 攻撃起動: `src/main/java/io/github/maaasu/astralRecord/feature/item/*`
- ホットバー操作: `src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- built-in skill 実行・アクションリング: `src/main/java/io/github/maaasu/astralRecord/feature/skill/*`

## ドキュメント一覧

| ファイル | 役割 |
|:--|:--|
| [[15_0.00-概要]] | 機能概要・対象範囲・メッセージ/ログ ID |
| [[15_3.01-イベント]] | 左クリック通常攻撃のイベント連携 |

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| [[03_README\|03-player]] | AstPlayer 取得、リソースステータス参照 |
| [[04_README\|04-item]] | `equipment.onUse.leftClickSkillId` / `leftClickCooldownTicks` / `rightClickSkillId` / `rightClickCooldownTicks` |
| [[08_README\|08-inventory]] | メインハンド装備参照 |
| [[13_README\|13-skill]] | built-in 通常攻撃発動、リソース消費、クールダウン |

## 更新ルール

- 武器クリック攻撃の起点、対象 `Action`、クールダウン開始条件を変更した場合は [[04_3.01-イベント]] と [[13_3.02-サービス]] を更新する。
- ホットバー操作やショートカット表示を変更した場合は [[08_3.01-イベント]] と [[08_3.02-サービス]] を更新する。
- built-in skill 実行、アクションリング、skilltree ガードを変更した場合は [[13_3.02-サービス]] と [[13_4.00-統合フロー]] を更新する。
- 本ディレクトリの本文は移行履歴としてのみ更新し、新しい仕様の正本にはしない。
