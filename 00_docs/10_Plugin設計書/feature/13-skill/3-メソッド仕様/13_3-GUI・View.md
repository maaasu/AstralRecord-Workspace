# 13_3-GUI・View

## 1. bind main GUI

クラス名: `SkillBindGui`
物理名: `open`

54 slot GUI を次の配置で描画する。

| slot | 内容 |
|---:|---|
| 0..35 | 所持 skill 一覧（36 件 / page） |
| 36..43 | active bind 1..8 |
| 44 | 保存 |
| 45 / 53 | 前 / 次 page |
| 46..48, 50..52 | preset 1..6 |
| 49 | 戻る |

一覧は所持 skill のうち active と `passiveBindRequired=true` の passive を表示する。ただし passive は情報表示だけで、一覧 click から active slot へ割り当てない。GUI に passive bind slot は描画しない。

## 2. スキルツリーノード解除確認GUI

クラス名: `SkillTreeEventHandler`

- 右クリックで解放済みノードを解除する前に、100ゴールドを消費することを明示した確認GUIを表示する。
- GUIには「解除する」「キャンセル」と「確認GUIを表示しない」の切替を配置する。切替をONにして解除を確定すると、以後の解除確認を省略する。
- 確認省略はプレイヤー単位の一時状態であり、スキルツリーワールドから離れた時点、または quit 時にリセットする。再入場後の最初の解除では確認GUIを再表示する。
- GUI操作時は対象アカウント、対象ノード、現在のスキルツリーワールド滞在を再検証し、無効な操作や inventory drag はキャンセルする。

## 3. 破棄確認 GUI

クラス名: `SkillBindGui`
物理名: `openConfirm`

dirty session の close、back、preset switch 前に共通 `ConfirmDialogView` を表示し、action と切替先 preset を holder へ保持する。

## 4. GUI 識別・pagination

クラス名: `SkillBindGui`
物理名: `isInventory`, `holder`, `skillId`, `normalizePage`, `totalPages`, `hasPreviousPage`, `hasNextPage`, `sortedSkills`, `presetSlot`, `presetIndexAtSlot`

専用 holder と PDC skill ID で操作対象を識別する。skill は ID 昇順、preset slot は 1..3 を 46..48、4..6 を 50..52 へ割り当てる。
