# Skilltree ノードカタログ

## 役割

この文書は、同じ能力を表すスキルツリーノードの名称・アイコン・lore・タグを統一するためのコンテンツ設計カタログです。AI を含む制作者は、新規ノードを作る前にこの文書と `40_filebase/35.features.skilltree/nodes/*.json` を確認します。

JSON のノード定義は、実際の `effects`、`pointType`、`pointCost`、表示情報の正本です。このカタログは JSON を置き換えず、再利用するノードの判断基準と採用済み表現を示します。

`lore` は任意項目です。省略したノードは、Pluginとエディターでノード固有の説明を表示しません。表示説明を定義するノードだけ、カタログの `lore` を対応する JSON へ設定します。

## 再利用の判断

`effects[]` の全要素を一組の能力として扱い、次のすべてが一致するときだけ既存の表現を流用します。個別の効果が一つだけ一致しても、ほかの効果が追加・削除・変更されている場合は流用しません。

- 効果種別と対象（例: `status` / `MAX_HEALTH`）
- 補正種別と数値（例: `FLAT` / `10`）
- 使用許可の場合は対象スキル ID

`effects[]` の組合せが変わる場合は別ノードを作ります。プレイヤーへ提示する役割、PP / CP の種別、コスト、職業条件、配置は進行設計上の属性であり、それだけを理由に同じ能力の表示名・アイコン・lore・タグを変えません。似た能力でも、異なる数値または異なる効果の組合せを同じ名前・アイコンで表してプレイヤーを誤認させてはいけません。

## 採用済みの共通表現

| カタログ ID | 効果 | 表示名 | アイコン | lore | タグ |
|---|---|---|---|---|---|
| `status-starter-foundation` | `MAX_HEALTH / FLAT / 5` + `MAX_MANA / FLAT / 5` + `MAX_ENERGY / FLAT / 5` | `&d旅立ちの記録` | `NETHER_STAR` | `&7序盤を戦い抜くための基礎を整える。` | `root`, `shared`, `core`, `early` |
| `status-strength-flat-1` | `STRENGTH` / `FLAT` / `1` | `&d紅蓮の星脈` | `BLAZE_POWDER` | `&7燃ゆる星の鼓動を宿し、筋力を高める。` | `status`, `primary`, `strength`, `ember` |
| `status-dexterity-flat-1` | `DEXTERITY` / `FLAT` / `1` | `&d銀矢の星脈` | `ARROW` | `&7銀の軌跡を見極め、器用さを高める。` | `status`, `primary`, `dexterity` |
| `status-intelligence-flat-1` | `INTELLIGENCE` / `FLAT` / `1` | `&d星詠みの星脈` | `ENCHANTED_BOOK` | `&7星々の理を読み解き、知力を高める。` | `status`, `primary`, `intelligence`, `astral` |
| `status-vitality-flat-1` | `VITALITY` / `FLAT` / `1` | `&d大樹の星脈` | `OAK_SAPLING` | `&7大樹の息吹を身に宿し、体力を高める。` | `status`, `primary`, `durability` |
| `status-agility-flat-1` | `AGILITY` / `FLAT` / `1` | `&d風渡りの星脈` | `FEATHER` | `&7風の精気を身にまとい、敏捷性を高める。` | `status`, `primary`, `agility`, `wind` |
| `status-luck-flat-1` | `LUCK` / `FLAT` / `1` | `&d巡星の星脈` | `EMERALD` | `&7巡る星の導きを受け、幸運を高める。` | `status`, `primary`, `luck` |
| `status-attack-flat-1` | `ATTACK` / `FLAT` / `1` | `&d暁刃の星脈` | `IRON_SWORD` | `&7暁の刃気をまとい、攻撃力を高める。` | `status`, `offense` |
| `status-defense-flat-1` | `DEFENSE` / `FLAT` / `1` | `&d玄岩の星脈` | `OBSIDIAN` | `&7大地の星核を身に宿し、防御力を高める。` | `status`, `defense`, `durability`, `stone` |
| `status-magic-defense-flat-1` | `MAGIC_DEFENSE` / `FLAT` / `1` | `&d星衣の星脈` | `ENCHANTED_GOLDEN_APPLE` | `&7星光の衣をまとい、魔法防御力を高める。` | `status`, `defense`, `astral` |
| `status-max-health-flat-10` | `MAX_HEALTH` / `FLAT` / `10` | `&d灯火の星脈` | `HEART_OF_THE_SEA` | `&7消えぬ星灯を胸に宿し、最大HPを高める。` | `status`, `resource`, `health`, `starlight` |
| `status-max-mana-flat-10` | `MAX_MANA` / `FLAT` / `10` | `&d蒼泉の星脈` | `AMETHYST_SHARD` | `&7天の蒼泉から魔力を汲み、最大MPを高める。` | `status`, `resource`, `mana`, `azure` |
| `status-max-energy-flat-5` | `MAX_ENERGY` / `FLAT` / `5` | `&d蒼穹の星脈` | `ENDER_PEARL` | `&7天駆ける蒼穹の息吹を宿し、最大ENを高める。` | `status`, `resource`, `energy` |
| `status-movement-speed-flat-1` | `MOVEMENT_SPEED` / `FLAT` / `1` | `&d迅風の星脈` | `SUGAR` | `&7迅風の流れに乗り、移動速度を高める。` | `status`, `agility`, `wind` |
| `status-mp-regen-flat-0.2` | `MP_REGEN` / `FLAT` / `0.2` | `&d還流の星脈` | `PRISMARINE_CRYSTALS` | `&7蒼き魔力の巡りを整え、MP回復力を高める。` | `status`, `resource`, `mana`, `azure` |
| `skill-swordsman-blade-counter` | `skill` / `swordsman_blade_counter` | `&f反照の剣星` | `WHITE_STAINED_GLASS` | `&7攻めの直後に刃を返し、敵の一撃を反撃へ変える。` | `defense` |

表の表示名には JSON に保存する Legacy color code を含めます。同じ能力を追加するときは、表の表示名、`icon`、`lore`、タグをすべて同一にします。各配置済みノードには、従来どおり一意の `nodeId` を割り当てます。

## 将来skill予約枠の暫定表現

予約枠は能力カタログには含めません。現行schemaで通常非表示のleafを確保するため、全予約枠で次の表示と空効果を共通利用します。

| 項目 | 値 |
|:--|:--|
| `effects` | `[]` |
| 表示名 | `&8未定の星座` |
| アイコン | `GRAY_DYE` |
| lore | `&8将来のスキル用に確保された予約枠。` |
| タグ | `[]` |
| コスト | 地域に応じた `pointType` / `pointCost: 0` |
| 条件 | 地域に応じた `classId` / `playerLevel: 2147483647` |

`playerLevel: 2147483647` は絶対ロックではなく、専用ロック項目がない現行schema内の暫定表現です。予約枠は後続nodeを持たないleafとし、実skill IDは追加しません。

## カタログの更新規約

- 新しい能力を採用したときは、対応する node JSON と同じ変更でこの表に追加します。
- 既存能力の表示名、アイコン、lore、タグ、コストを変更するときは、カタログの該当行と使用中の node JSON を同時に確認します。
- 同じ能力を職業別ノードへ置く場合も、このカタログの表現を使います。プレイヤーへ提示する役割、職業条件、配置構造の違いだけを理由に別の名称・アイコン・lore・タグを作りません。
- このカタログは地域や座標を管理しません。地域・配置構造の方針は [[35-skilltree]]、最終座標と edge は `structures/*.json` を正本とします。
