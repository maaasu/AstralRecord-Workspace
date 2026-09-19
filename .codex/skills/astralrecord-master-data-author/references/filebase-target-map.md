# ファイルベース 対象マップ

マスター設計書を読んだ後にこのマップを使う。編集前に必ず対象スキーマを読む。

## 主要ファイル

| 用途 | ディレクトリ | スキーマ |
|:--|:--|:--|
| 共通状態名 | プラグインソース | `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/model/StatusType.kt` |
| アイテム 共通のフィールド | `40_filebase/10.features.item` | `40_filebase/10.features.item/docs.item.YAMLスキーマ定義.md` |
| 装備 | `40_filebase/10.features.item/equipment` | `40_filebase/10.features.item/equipment/docs.equipment.YAMLスキーマ定義.md` |
| 素材 | `40_filebase/10.features.item/material` | `40_filebase/10.features.item/material/docs.material.YAMLスキーマ定義.md` |
| 消耗品 | `40_filebase/10.features.item/consumable` | `40_filebase/10.features.item/consumable/docs.consumable.YAMLスキーマ定義.md` |
| 職業 | `40_filebase/20.features.class` | `40_filebase/20.features.class/docs.class.YAMLスキーマ定義.md` |
| スキル | `40_filebase/30.features.skill` | `40_filebase/30.features.skill/docs.skill.YAMLスキーマ定義.md` |
| スキルツリー | `40_filebase/35.features.skilltree` | `40_filebase/35.features.skilltree/docs.skilltree.YAMLスキーマ定義.md` |
| 敵 | `40_filebase/40.features.mob/enemy` | `40_filebase/40.features.mob/docs.mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/enemy/docs.enemy.YAMLスキーマ定義.md` |
| ボス | `40_filebase/40.features.mob/boss` | `40_filebase/40.features.mob/docs.mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/boss/docs.boss.YAMLスキーマ定義.md` |
| NPC | `40_filebase/40.features.mob/npc` | `40_filebase/40.features.mob/docs.mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/npc/docs.npc.YAMLスキーマ定義.md` |
| モブスポナー | `40_filebase/41.features.mob.spawner` | `40_filebase/41.features.mob.spawner/docs.spawner.YAMLスキーマ定義.md` |
| 採集 | `40_filebase/42.features.gathering` | `40_filebase/42.features.gathering/docs.gathering.YAMLスキーマ定義.md` と下位種別スキーマ |
| 採集 スポナー | `40_filebase/43.features.gathering.spawner` | `40_filebase/43.features.gathering.spawner/docs.spawner.YAMLスキーマ定義.md` |
| ショップ | `40_filebase/45.features.shop` | `40_filebase/45.features.shop/docs.shop.YAMLスキーマ定義.md` |
| クエスト | `40_filebase/47.features.quest` | `40_filebase/47.features.quest/docs.quest.YAMLスキーマ定義.md` |
| クエスト掲示板 | `40_filebase/48.features.quest_board` | `40_filebase/48.features.quest_board/docs.quest_board.YAMLスキーマ定義.md` |
| ワールド | `40_filebase/60.features.world` | `40_filebase/60.features.world/docs.world.YAMLスキーマ定義.md` |
| バフ | `40_filebase/70.shared.buff` | `40_filebase/70.shared.buff/docs.buff.YAMLスキーマ定義.md` |
| ドロップ報酬プール | `40_filebase/80.shared.loot/pool` | `40_filebase/80.shared.loot/pool/docs.pool.YAMLスキーマ定義.md` |
| ドロップ報酬テーブル | `40_filebase/80.shared.loot/table` | `40_filebase/80.shared.loot/table/docs.table.YAMLスキーマ定義.md` |

## ID の指針

- 小文字 snake_case を使う。
- デバッグ用の名前より本番向けの名前を優先する。
- 領域または機能接頭辞は、依頼の文脈が定義しており所有権の明確化に役立つ場合だけ使う。
- ドロップ報酬、ショップ、レシピ、装備、モブ定義から参照されるため、アイテム ID は安定して単純に保つ。

## 参照の接頭辞

| 対象 | 接頭辞 |
|:--|:--|
| アイテム | `item:` |
| スキル | `skill:` |
| バフ | `buff:` |
| モブ | `mob:` |
| クラス | `class:` |
| レシピ | `recipe:` |
| ルーン | `rune:` |
| セット 効果 | `set:` |

参照は対象スキーマまたは近隣ファイルがすでに使っている形式で記載する。
