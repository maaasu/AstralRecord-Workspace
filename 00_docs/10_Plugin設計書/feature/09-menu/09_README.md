# 09_README

このディレクトリは `feature/menu` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/menu/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/menu/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/menu/view/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/menu/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/menu/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/sell/*`

## ドキュメント一覧（推奨順）

1. [[09_0.00-概要]]
2. [[09_1.00-モデル定義]]
3. [[09_2.00-ユースケース]]
4. [[09_3.00-索引]]
5. [[09_4.00-統合フロー]]
6. [[09_5.00-例外・ログ・運用]]
7. [[09_9.00-未決事項]]（必要時）

## 依存 feature

- `player`
- `account`
- `inventory`
- `currency`
- `status`
- `party`
- `shop`
- `adventurerecord`
- `class` / `playerclass`
- `player-interaction`: `DROP_ITEM`の競合調停とクラフトショートカットdrop候補

## 更新ルール（変更時に必ず更新する章）

- 画面構成・遷移・ショートカット仕様の変更:
  - [[09_1.00-モデル定義]]
  - [[09_3.07-GUI・View]]
  - [[09_4.00-統合フロー]]
- `/menu` コマンドやイベント起点の変更:
  - [[09_3.01-イベント]]
  - [[09_3.03-コマンド]]
  - [[09_5.00-例外・ログ・運用]]
- 永続設定（ショートカット設定等）の変更:
  - [[09_3.04-リポジトリ]]
  - [[09_4.00-統合フロー]]
- クラフトショートカット用ダミーのdrop抑止、候補tier、item entity清掃の変更:
  - [[09_3.01-イベント]]
  - [[09_4.00-統合フロー]]
  - [[28_3.01-イベント]]

## 実装メモ

- 2026-05-30: クラフトスロットの `STATUS` は互換識別値として維持し、統合 `プレイヤー情報` 画面を開く。
- 2026-05-30: `STATUS` ショートカットの lore は current HP / MP / EN を表示せず、`ATTACK` / `MELEE_ATTACK` / `RANGED_ATTACK` / `MAGIC_ATTACK` / `DEFENSE` / `MAGIC_DEFENSE` を表示する。
- 2026-05-30: メインメニュー slot 33 は職業 GUI ではなくパーティー GUI を開く。職業 GUI は通常メニューからは開かず、ADMIN の引数なし `/class` または mob feature の NPC interaction (`type=CLASS`) から開く。
- 2026-05-30: 通常メニュー系 GUI 表示中は下部プレイヤーインベントリをダミーで埋める。装備 GUI、ゴミ箱 GUI、スキル選択など実アイテム操作が必要な画面は対象外。
- 2026-05-30: パーティー GUI は menu 系の戻る仕様に揃え、slot 49 で GUI セッション内の直前画面へ戻る。パーティー離脱/解散は slot 51 に配置し、メンバー/招待はプレイヤーヘッドで表示する。
- 2026-06-09: `sell` は独立実装を持つが、docs では menu の売却 GUI として吸収する。`/sell`、売却 GUI、売却確定は本 feature の対象実装パスに含める。
- 2026-06-09: `party` / `currency` / `shop` / `adventurerecord` はそれぞれ独立 feature を正本とし、menu はメインメニューや戻る操作などの導線のみを担当する。
- 2026-06-09: 職業 GUI は class / playerclass 側を正本とし、menu は通常プレイヤー向けメインメニュー導線を持たない。ADMIN の引数なし `/class` と mob feature の NPC interaction (`type=CLASS`) は class / playerclass 側で扱う。
- 2026-06-13: 装備強化 GUI は menu からの導線と inventory GUI 上の操作フローを担当し、必要素材・成功率・失敗時挙動の定義自体は item master / equipment schema を正本とする。
- 2026-06-13: 装備強化 GUI は装備を一旦セット可能とし、強化不可理由は実行アイコンへ表示する。GUI 内専用の閉じるボタンは置かない。
- 2026-07-12: `HotbarShortcutGuiHolder` 対象 GUI はプレイヤーインベントリの BAG 制御列中央 `slot 26` をインベントリ情報表示として維持し、専用の閉じるボタンを配置しない。ホットバー9枠はカスタムGUI中も割当アイテムを表示する。
- 2026-07-14: 装備 GUI の `slot 16` は将来用ペットスロットとし、戻るボタンは標準位置 `slot 49` に配置する。空の防具枠は黒灰色の皮防具、空のメイン・オフハンド枠は実アイテムと異なる額縁系アイコンで表示する。
- 2026-07-16: メインメニューは個人操作、交流・活動、補助機能の 3 行に整理する。従来のアカウント情報・ステータス画面は `PlayerDetailGui` へ統合し、プレイヤーヘッドに基本情報、アカウント、クラス、資産と活動をまとめる。現在地は world master の表示名だけを表示する。
- 2026-07-16: 戻る操作は `AstPlayer.guiNavigationState` が保持する GUI セッション内の直前画面を開く。GUI を閉じた場合は履歴を破棄し、戻り先がない画面では戻るボタンを表示しない。
- 2026-07-17: アイテム転送 GUI の数量規則を左クリック1個、右クリック半分、Shift+左クリック1スタック、Shift+右クリック全量へ統一する。通貨・ストレージへの収納時の Shift+右は BAG・ホットバー全体の同一アイテムを対象にする。
- 2026-07-19: メインメニューのバフ専用アイコンを削除し、統合プレイヤー情報 GUI の「現在のバフ」から対象プレイヤーのバフ詳細 GUI を開く。
- 2026-07-19: メインメニューとクラフト欄の共通項目は `MenuIconDefinition` を正本とし、`MenuIconFactory` が表示先ごとに独立した `ItemStack` を生成する。
- 2026-07-19: プレイヤー依存のメニュー描画値は `AstPlayer` へ追加せず、`PlayerGuiRenderContextFactory` が生成する不変 `PlayerGuiRenderContext` にスナップショット化する。

## 追記（ゴミ箱GUI）
- ゴミ箱GUI追加に伴い、[[09_1.00-モデル定義]]・[[09_3.01-イベント]]・[[09_3.07-GUI・View]]・[[09_4.00-統合フロー]] を更新。

## 追記（2026-06-01 プレイヤー一覧・パーティーGUI改修）
- プレイヤー一覧 GUI を共通化し、用途ごとに抽出条件と遷移先を切り替えて流用する。
- メインメニューにプレイヤー一覧導線を追加し、参加中プレイヤーの基本情報確認と詳細画面遷移を提供する。
- `/player info <playerName>` から対象プレイヤーの詳細 GUI を直接開けるようにする。
- パーティー GUI はリーダーを上段中央、メンバーを下段中央から左右へ広げて表示する。
- パーティーリーダーはメンバー頭アイコンから昇格・キック GUI を開ける。
- プレイヤー情報 GUI の「現在のバフ」には獲得中のバフ名一覧を表示し、表示名のカラーコードは除去する。
