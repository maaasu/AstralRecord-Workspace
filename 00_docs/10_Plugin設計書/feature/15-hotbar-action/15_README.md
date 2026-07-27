# 15_README

旧ホットバーアクションは独立 feature を廃止し、入力調停を player-interaction、武器アクションを item、ホットバー状態を inventory、アクションリングと skill 実行を skill へ移管している。本ディレクトリは責務境界を示す移行資料として扱う。

## 対象実装パス

独立した実装 package は存在しない。現在の対象は次の移管先である。

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/interaction/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/interaction/PlayerInteractionGatewayEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/event/ItemWeaponAttackEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/ItemWeaponAttackService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/*`

## ドキュメント一覧（推奨順）

1. [[15_0.00-概要]]
2. [[15_3.01-イベント]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| [[03_README\|03-player]] | `AstPlayer` と `AccountMode` |
| [[04_README\|04-item]] | 武器マスタ、装備要件、耐久値、左クリック候補 |
| [[08_README\|08-inventory]] | メインハンドの item 解決とホットバー状態 |
| [[13_README\|13-skill]] | skill 発動、クールダウン、アクションリング |
| [[28_README\|28-player-interaction]] | 入力正規化、候補選択、二重実行防止 |

## 更新ルール（変更時に必ず更新する章）

- 入力候補の tier、距離、`stableOrder`、claim 方針を変更した場合は [[15_3.01-イベント]] と player-interaction feature を更新する。
- 武器の発動条件、装備要件、耐久値、skill 発動条件を変更した場合は [[15_0.00-概要]]、[[15_3.01-イベント]]、item / skill feature を更新する。
- ホットバーの保存・描画を変更した場合は inventory feature を更新する。本ディレクトリへ新しい正本仕様を戻さない。
