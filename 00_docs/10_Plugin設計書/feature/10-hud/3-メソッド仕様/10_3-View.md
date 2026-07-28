# 10_3-View

## 1. status ActionBar 描画

クラス名: `PlayerHudView`
物理名: `renderActionBar`

HP、MP、ENG の現在値 / 最大値を表示し、`MAX_SHIELD > 0` の場合だけ SH を追加する。

## 2. 一時 ActionBar 描画

クラス名: `PlayerHudView`
物理名: `renderDodgeWindowActionBar`, `renderWallClingActionBar`

残り割合を 0..1 へ clamp し、28 文字の `DODGE` または `WALL` progress bar を表示する。

## 3. vanilla bar 描画

クラス名: `PlayerHudView`
物理名: `renderBars`

| status | Bukkit 表示 |
|---|---|
| HP / MAX_HEALTH | health attribute の最大値を 20 に保ち、health 0.5..20 へ写像 |
| EN / MAX_ENERGY | food level 0..20、saturation 0 |
| MP / MAX_MANA | experience progress 0..1 |
| shield / MAX_SHIELD | armor attribute 0..20 |

## 4. sidebar 描画・解除

クラス名: `PlayerHudView`
物理名: `renderSidebar`, `removeSidebar`

plugin 専用 objective `astral_info` を player scoreboard に作成・再描画する。performance 表示が有効な場合だけ MSPT / ping を含め、挑戦中 boss がある場合だけ boss 名、death、時間、参加者を追加する。非 gameplay mode では同 objective だけを unregister する。

## 5. tab list 描画

クラス名: `PlayerHudView`
物理名: `renderTabList`

header に `ASTRAL RECORD` を表示し、performance 表示が有効な場合だけ MSPT と ping を色分けして追加する。
