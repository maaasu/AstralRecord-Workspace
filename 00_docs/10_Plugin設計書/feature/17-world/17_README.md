# 17_README

`world` feature は API 由来のワールド定義を読み込み、Bukkit ワールドとの対応付け、ロード、スポーン地点転送、拠点・オーバーワールド間の導線を提供する。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/world/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/teleport/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/interaction/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/effect/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/timing/*`

## ドキュメント一覧（推奨順）

1. [[17_0.00-概要]]
2. [[17_1.00-モデル定義]]
3. [[17_3.00-サービス]]
4. [[17_3.01-コマンド]]
5. [[17_4.00-統合フロー]]
6. [[17_5.00-例外・ログ・運用]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| infrastructure / API | `/api/world` の定義取得と filebase master seed |
| player / teleport | プレイヤー状態、タイトル表示、安全な非同期転送 |
| inventory / currency | 通常の拠点帰還で消費するゴールド |
| mob | 管理ワールド内の非 AstralRecord Mob 排除 |
| interaction / menu | スポーン地点入力候補、転送先選択 GUI |

## 更新ルール（変更時に必ず更新する章）

- `WorldMasterData` または API の world schema を変更した場合は [[17_1.00-モデル定義]] と API 設計書を更新する。
- ロード・キャッシュ・スポーン解決を変更した場合は [[17_3.00-サービス]] と [[17_4.00-統合フロー]]を更新する。
- `/world`、`/wtp`、権限を変更した場合は [[17_3.01-コマンド]]を更新する。
- 拠点帰還の待機、費用、補償、ゲートウェイ導線を変更した場合は [[17_4.00-統合フロー]] と [[17_5.00-例外・ログ・運用]]を更新する。
