# 10_3-View

## 1. status ActionBar 描画

クラス名: `PlayerHudView`
物理名: `renderActionBar`

HP、MP、ENG の現在値 / 最大値を表示し、`MAX_SHIELD > 0` の場合だけ SH を追加する。Shield リチャージ中は現在 Shield が 0 であることを示しつつ、通常の SH 表示をオレンジ色の `SH RC` と 0.1 秒単位の残り時間へ置換する。有効な状態異常は固定優先順位で最大3件を右側へ併記し、種別ごとの色、太字の識別記号・表示名、残り秒数で装飾する。4件目以降は `+N` で省略数を示す。

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
| MP / MAX_MANA | `MAX_ABSORPTION` を 20 に保ち、absorption 0..20 へ写像。最大10個の黄色いハートで表示。独自HP / Shieldダメージでは消費しない |
| account EXP / player level | Paper の経験値表示パケットへレベル内進捗とアカウントプレイヤーレベルを渡す。vanilla経験値の実値は変更しない |
| shield / MAX_SHIELD | armor attribute 0..20。リチャージ中は現在 Shield 0 のため 0 |

## 4. sidebar 描画・解除

クラス名: `PlayerHudView`
物理名: `renderSidebar`, `removeSidebar`

plugin 専用 objective `astral_info` を player scoreboard に作成・再描画する。プレイヤーはレベル値のみを表示し、クラス名・クラスレベルの直下に現在クラスの経験値進捗バーを表示する。経験値進捗バーは上下幅の小さいブロック文字 `▰` を使用する。performance 表示が有効な場合だけ MSPT / ping を含め、挑戦中 boss がある場合だけ boss 名、death、時間、参加者を追加する。`BUFF_SIDEBAR_DISPLAY` が有効な場合は、有効バフを獲得順に最大5件、buff / debuff の色分け、残り時間付きで追加する。超過分は最後の表示行へ「ほかN件」と付記する。全体は15行以内とし、必要時は performance 情報を省略し、boss 表示中は利用可能行数までバフ件数を縮小する。非 gameplay mode では同 objective だけを unregister する。

## 5. tab list 描画

クラス名: `PlayerHudView`
物理名: `renderTabList`

header に `ASTRAL RECORD` を表示し、performance 表示が有効な場合だけ MSPT と ping を色分けして追加する。

各プレイヤーのリスト名は、ログインデータ反映時・クラス変更時・クラスマスター再読込時に `PlayerClassService.updatePlayerListName` が更新し、正式クラス名 `name` のタグをプレイヤー名の左へ表示する。
