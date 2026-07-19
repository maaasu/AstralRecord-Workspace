# 28-player-interaction

このディレクトリは、プレイヤーの同一物理入力から複数の AstralRecord アクションが競合する場合の共通調停仕様をまとめる。
採番・命名・参照ルールは [[README]] に従う。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/shared/interaction/*`
- `src/main/java/io/github/maaasu/astralRecord/shared/interaction/PlayerInteractionGatewayEventHandler.java`
- 各 feature が登録する `PlayerInputResolver`

## ドキュメント一覧（推奨順）

1. [[28_0.00-概要]]
2. [[28_1.00-モデル定義]]
3. [[28_3.01-イベント]]
4. [[28_3.02-サービス]]
5. [[28_4.00-統合フロー]]

## 依存 feature

- `player`: 入力ロック、プレイヤーモードのドロップ抑止、壁張り付き・回避などのプレイヤー制御、退出ライフサイクル
- `mob`: NPC 左右クリック候補
- `teleporter`: ウェイストーン左右クリック候補
- `skill`: アクションリング操作・表示候補
- `skilltree`: ノード操作、管理用設置操作、設定中のホットバー切替抑止候補
- `item`: bundle / potion 使用、武器攻撃候補
- `combat`: プレイヤー起点の entity 攻撃候補
- `gathering`: 採集開始候補
- `spawner`: Mob / Gathering spawner の設置・削除候補
- `menu`: クラフトショートカット用ダミーのドロップ抑止候補
- `inventory`: ホットバー変更後の非競合表示同期
- `world`: 拠点・オーバーワールドのスポーン地点スニーク候補
- `boss`: ボス入口スニーク候補、ボス中のドロップ抑止候補

## 更新ルール（変更時に必ず更新する章）

- ingress event、入力 family、token 相関方式の変更:
  - [[28_1.00-モデル定義]]
  - [[28_3.01-イベント]]
  - [[28_4.00-統合フロー]]
- family ごとの候補優先順位、距離比較、tie-break の変更:
  - [[28_0.00-概要]]
  - [[28_1.00-モデル定義]]
  - [[28_3.02-サービス]]
- feature 候補の追加・削除:
  - [[28_3.02-サービス]]
  - 対象 feature のイベント・サービス設計書
- 遅延実行時の`executionGuard`、`executeIfValid()`、stale target再検証の変更:
  - [[28_1.00-モデル定義]]
  - [[28_3.02-サービス]]
  - [[28_4.00-統合フロー]]
- `InputClaimPolicy`、ledgerの`claimed` / `cancelRequested`、event cancel、vanilla pass-through、候補なし、winner 失敗時挙動の変更:
  - [[28_1.00-モデル定義]]
  - [[28_3.02-サービス]]
  - [[28_3.01-イベント]]
  - [[28_4.00-統合フロー]]
- `HOTBAR_SLOT` の非競合 observer を追加・変更:
  - [[28_3.01-イベント]]
  - 対象 observer のイベント設計書

## 正本境界

- `RIGHT_CLICK`、`LEFT_CLICK`、`BLOCK_MUTATION`、`DROP_ITEM`、`HOTBAR_SLOT`、`SNEAK` の入力相関、候補収集、優先順位、距離比較、勝者一件の実行は本 feature を正本とする。
- NPC の action 内容、ウェイストーン解除、アイテム効果、スキル発動、ドロップ抑止、ワールド移動など、勝者決定後の業務処理は各 feature を正本とする。
- hotbar変更後のstatus・equipment・item表示更新は競合候補ではなくobserverとし、cancel済みeventでは実処理しない。
- 各 feature の Bukkit event priority や listener 登録順を、AstralRecord 内の排他制御として使用しない。
