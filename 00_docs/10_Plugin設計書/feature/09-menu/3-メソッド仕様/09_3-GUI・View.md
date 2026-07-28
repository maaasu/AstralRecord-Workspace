# 09_3-GUI・View

## 1. menu facade

クラス名: `MenuView`

主な物理名:

- `open`: メインメニュー。
- `openEquipmentGui`: 装備画面。
- `openBuff` / `renderBuff`: バフ画面。
- `openClass`: クラス画面。
- `openCurrency`: 通貨画面。
- `openGuide` / `openGuideDetail`: ガイド一覧・詳細。
- `openTrash` / `renderTrash` / `openTrashConfirm`: ゴミ箱。
- `openSell` / `renderSell` / `openSellConfirm`: sell feature が利用する共通 view。
- `openStorage` / `renderStorage`: storage feature が利用する共通 view。
- `renderCraftShortcuts` / `clearCraftShortcuts` / `removeCraftShortcutItems`: craft shortcut 表示管理。
- `getMenuScreen`、`getPageIndex`、`getContentId`: `MenuInventoryHolder` の画面状態取得。

各画面の slot 判定と描画は `view/screen/*ScreenView` へ委譲する。

## 2. メイン画面

クラス名: `MainMenuScreenView`
物理名: `render`

54 slot inventory に [[09_2-ユースケース]].メインメニューを開く の配置で icon を描画する。プレイヤー依存 lore は `PlayerGuiRenderContext` だけを参照し、描画中に各 repository を再取得しない。

## 3. アイコン生成

クラス名: `MenuIconFactory`
物理名: `create`, `equipmentDetails`, `currencyDetails`, `returnToBaseDetails`, `openHint`, `executeHint`

`MenuIconDefinition` の material、色、説明へ描画コンテキスト由来の詳細と操作 hint を組み合わせる。

## 4. プレイヤー一覧・詳細

クラス名: `PlayerListGui`、`PlayerDetailGui`
物理名: `open`

online player をページ表示し、選択対象のプロフィール・ステータス・装備情報を詳細画面へ描画する。holder には対象 UUID、用途、ページを保持する。
