# 15 ホットバーアクション

## 対象実装パス

- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\equipment\event\`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\equipment\service\`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\equipment\executor\`

## ドキュメント一覧

| ファイル | 役割 |
|:--|:--|
| [[15_0.00-概要]] | 機能概要・対象範囲・メッセージ/ログ ID |
| [[15_3.01-イベント]] | 左クリック通常攻撃のイベント連携 |

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| [[03_README\|03-player]] | AstPlayer 取得、リソースステータス参照 |
| [[04_README\|04-item]] | `equipment.onUse.leftClickSkillId` / `leftClickCooldownTicks` |
| [[08_README\|08-inventory]] | メインハンド装備参照 |
| [[13_README\|13-skill]] | built-in 通常攻撃発動、リソース消費、クールダウン |

## 更新ルール

- 武器左クリック通常攻撃の起点、対象 `Action`、クールダウン開始条件を変更した場合は [[15_3.01-イベント]] を更新する。
- ホットバー入力の責務範囲、メッセージ ID、ログ ID を変更した場合は [[15_0.00-概要]] を更新する。
- `equipment.onUse` の通常攻撃連携を変更した場合は [[04_1.00-モデル定義]] も合わせて更新する。
