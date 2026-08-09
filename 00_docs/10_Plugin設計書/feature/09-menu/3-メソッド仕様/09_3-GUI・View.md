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

54 slot inventory に [[09_2-ユースケース]].メインメニューを開く の配置で icon を描画する。クエスト icon は slot `23` に配置する。プレイヤー依存 lore は `PlayerGuiRenderContext` だけを参照し、描画中に各 repository を再取得しない。

## 3. アイコン生成

クラス名: `MenuIconFactory`
物理名: `create`, `equipmentDetails`, `currencyDetails`, `returnToBaseDetails`, `openHint`, `executeHint`

`MenuIconDefinition` の material、色、説明へ描画コンテキスト由来の詳細と操作 hint を組み合わせる。

## 4. プレイヤー一覧・詳細

プロフィール詳細は一画面へ集約し、内部 world ID ではなく world 表示名、status は compact 表示値を使用する。

クラス名: `PlayerListGui`、`PlayerDetailGui`
物理名: `open`

online player をページ表示し、選択対象のプロフィール・ステータス・装備情報を詳細画面へ描画する。holder には対象 UUID、用途、ページを保持する。

詳細画面は slot `4` にプロフィール、`10` から `16` にリソース、基本能力値、攻撃、防御、属性、状態異常、ユーティリティの全 status category、`30` にクラス、`31` にスキル情報、`32` に有効バフ、`38` にトレード、`42` に party 招待を配置する。各 status category icon をクリックすると、そのカテゴリに属する status だけを1 status 1 itemで表示する詳細 GUIへ遷移する。詳細 item は `StatusSnapshot` のステータス名、説明、現在値、基礎値、合計補正を表示し、項目数に応じてページングする。属性・状態異常の status は種類ごとの表示色を付け、属性・状態異常カテゴリ内ではダメージ・付与確率などの増加系、耐性系、貫通系の順に表示する。

スキル情報 icon の lore は、現在選択中の `SkillBindPreset` から解決したアクティブバインドを表示し、仕切り線の後へパッシブバインドを表示する。クリック後は使用許可スキル一覧と習得済みスキル一覧を選択する画面を開く。使用許可一覧は `SkillPermissionService`、習得済み一覧は `LearnedSkillService` を正本とし、各スキルをマスター定義の icon と `SkillPresentationUtil` による名称・説明・効果 lore でページ表示する。

## 5. 画面固有の描画不変条件

- `MenuIconFactory` は共有 icon 定義から呼出ごとに独立した `ItemStack` を生成する。通貨詳細は最大 10 件とし、超過時は ellipsis を加える。
- equipment 画面は main hand / off hand に専用の空 marker を使い、accessory GUI slot を `AccessorySlotType` へ対応付ける。空 slot の自動選択は同じ equipment tag category 内に限定する。

equipment 画面は 54 slot とし、次の配置と空 marker を正本とする。

| 用途 | GUI slot | 空 marker / 将来枠 |
|:--|:--|:--|
| player status / pet / back | `0` / `16` / `49` | `PLAYER_HEAD` / `SADDLE` / `SPECTRAL_ARROW` |
| main hand / off hand | `19` / `21` | `ITEM_FRAME` / `GLOW_ITEM_FRAME` |
| head / chest / legs / feet | `11` / `20` / `29` / `38` | 対応する leather armor。空 marker の RGB は `(48,48,48)` |
| memory 1 / 2 | `27` / `36` | `HOPPER` |
| amulet | `23` | `CHEST_MINECART` |
| talisman 1 / 2 | `31` / `33` | `FURNACE_MINECART` |
| core | `32` | `HOPPER_MINECART` |
| relic 1 / 2 | `39` / `43` | `TNT_MINECART` |
| charm 1 / 2 / 3 | `40` / `41` / `42` | `MINECART` |
| gauge large / medium / small | `26` / `35` / `44` | `SPAWNER` |

accessory slot の逆引きは `23=AMULET`、`31=TALISMAN_1`、`33=TALISMAN_2`、`32=CORE`、`39=RELIC_1`、`43=RELIC_2`、`40..42=CHARM_1..3` とする。同一 category に複数 slot がある場合は番号の小さい空 slot から選び、同一 category に空きがなければ `-1` とする。

## 5.5. 装備加工画面

クラス名: `EquipmentProcessingMenuScreenView`
物理名: `render`

修理と強化を `MenuScreen.EQUIPMENT_PROCESSING` の 54 slot GUI に集約する。`MenuInventoryHolder.contentId` は `repair` または `enhancement` とする。

| 用途 | GUI slot | 内容 |
|:--|---:|:--|
| ガイド / 修理タブ / 強化タブ / 情報 | `10` / `12` / `14` / `16` | 操作説明、モード切替、次の効果・費用 |
| 対象装備 | `20` | 強化時は移動してセット。修理時は通常クリックでプレビューのみ |
| 消費素材 | `22..24` | 強化に使う実アイテムアイコンと必要数 / 所持数 |
| ゴールド / 実行 | `25` / `26` | 必要量 / 所持量、強化実行または修理操作案内 |

修理モードでは、下段の装備を `SHIFT_LEFT` でクリックすると、装備 entry を移動せずその場で耐久値を最大まで回復する。通常クリックは修理内容のプレビューだけを表示する。強化モードでは通常クリックで対象装備を上段へ退避し、消費素材・ゴールド・次のステータス増加・耐久値増加・成功率・失敗時挙動を確認してから実行する。失敗時に装備破壊となる定義は、実行ボタンを二度クリックして確認する。

## 6. クラフトショートカット描画

クラス名: `CraftShortcutView`
物理名: `renderCraftShortcuts`

プレイヤー情報 shortcut の status lore は、`StatusValue` の合計範囲が基礎範囲と異なる項目だけを `StatusType` のカタログ順に表示する。表示上限は8件とし、超過時は末尾へ `… ほかN件` を追加する。変更項目がない場合と status 未取得の場合は、それぞれ判別できる案内を表示する。
