# 11_3-GUI・View

## 1. 設定 GUI 表示

クラス名: `PlayerSettingGui`
物理名: `open`, `refresh`

54 slot の「プレイヤー設定」を開き、snapshot または draft 値から icon を描画する。

| slot | key / 操作 |
|---:|---|
| 20 | `DAMAGE_LOG_DISPLAY` |
| 21 | `DAMAGE_LOG_MESSAGE` |
| 22 | `PARTICLE_DENSITY` |
| 23 | `PERFORMANCE_INFO_DISPLAY` |
| 24 | `DROP_LOG_DISPLAY` |
| 25 | `AUTO_SAVE_MESSAGE` |
| 26 | `BUFF_SIDEBAR_DISPLAY` |
| 27 | `ARMOR_DISPLAY` |
| 28 | `ACTION_RING_HOLD_SELECT`（`TRIDENT` icon） |
| 49 | 前画面へ戻る |
| 53 | icon を置かない管理者用 super mode secret slot |

`TEMP_DROP_DISPLAY` と `TEMP_BLOCK_DISPLAY` は GUI に表示せず、コマンドから変更する。

`ACTION_RING_HOLD_SELECT` は既定 `false` で、`true` のときだけ右クリック長押し選択を使う。保存後は inventory を再送して、hotbar 内の主武器のクライアント表示を直ちに切り替える。

## 2. GUI 識別・slot 解決

クラス名: `PlayerSettingGui`
物理名: `isInventory`, `getKeyAtSlot`, `getUserId`

専用 holder で GUI と user UUID を識別し、表示対象 slot だけを `PlayerSettingKey` へ変換する。secret slot は通常 key 解決に含めない。
