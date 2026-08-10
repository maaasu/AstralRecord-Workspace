# Skilltree ノードカタログ

## 役割

この文書は、同じ能力を表すスキルツリーノードの名称・アイコン・lore・タグを統一するためのコンテンツ設計カタログです。AI を含む制作者は、新規ノードを作る前にこの文書と `40_filebase/35.features.skilltree/nodes/*.json` を確認します。

JSON のノード定義は、実際の `effects`、`pointType`、`pointCost`、表示情報の正本です。このカタログは JSON を置き換えず、再利用するノードの判断基準と採用済み表現を示します。

## 再利用の判断

`effects[]` の全要素を一組の能力として扱い、次のすべてが一致するときだけ既存の表現を流用します。個別の効果が一つだけ一致しても、ほかの効果が追加・削除・変更されている場合は流用しません。

- 効果種別と対象（例: `status` / `MAX_HEALTH`）
- 補正種別と数値（例: `FLAT` / `10`）
- 使用許可の場合は対象スキル ID

`effects[]` の組合せが変わる場合は別ノードを作ります。プレイヤーへ提示する役割、PP / CP の種別、コスト、職業条件、配置は進行設計上の属性であり、それだけを理由に同じ能力の表示名・アイコン・lore・タグを変えません。似た能力でも、異なる数値または異なる効果の組合せを同じ名前・アイコンで表してプレイヤーを誤認させてはいけません。

## 採用済みの共通表現

| カタログ ID | 効果 | 表示名 | アイコン | lore | タグ | 既存ノード例 |
|---|---|---|---|---|---|---|
| `status-strength-flat-1` | `STRENGTH` / `FLAT` / `1` | `&d紅蓮の星脈` | `BLAZE_POWDER` | `&7燃ゆる星の鼓動を宿し、筋力を高める。` | `status`, `primary`, `strength`, `ember` | `1013`, `1038`, `1043` |
| `status-agility-flat-1` | `AGILITY` / `FLAT` / `1` | `&d風渡りの星脈` | `FEATHER` | `&7風の精気を身にまとい、敏捷性を高める。` | `status`, `primary`, `agility`, `wind` | `1014`, `1042`, `1046` |
| `status-intelligence-flat-1` | `INTELLIGENCE` / `FLAT` / `1` | `&d星詠みの星脈` | `ENCHANTED_BOOK` | `&7星々の理を読み解き、知力を高める。` | `status`, `primary`, `intelligence`, `astral` | `1015`, `1039`～`1041` |
| `status-dexterity-flat-1` | `DEXTERITY` / `FLAT` / `1` | `&d銀矢の星脈` | `ARROW` | `&7銀の軌跡を見極め、器用さを高める。` | `status`, `primary`, `dexterity` | `1023`, `1044`, `1045` |
| `status-max-health-flat-10` | `MAX_HEALTH` / `FLAT` / `10` | `&d灯火の星脈` | `HEART_OF_THE_SEA` | `&7消えぬ星灯を胸に宿し、最大HPを高める。` | `status`, `resource`, `health`, `starlight` | `1016` |
| `status-max-mana-flat-10` | `MAX_MANA` / `FLAT` / `10` | `&d蒼泉の星脈` | `AMETHYST_SHARD` | `&7天の蒼泉から魔力を汲み、最大MPを高める。` | `status`, `resource`, `mana`, `azure` | `1017` |

表の表示名には JSON に保存する Legacy color code を含めます。同じ能力を追加するときは、表の表示名、`icon`、`lore`、タグをすべて同一にします。表中の「既存ノード例」は再利用可能な表現を探す起点であり、ノード ID 自体を再利用する意味ではありません。各配置済みノードには、従来どおり一意の `nodeId` を割り当てます。

## カタログの更新規約

- 新しい能力を採用したときは、対応する node JSON と同じ変更でこの表に追加します。
- 既存能力の表示名、アイコン、lore、タグ、コストを変更するときは、カタログの該当行と使用中の node JSON を同時に確認します。
- 同じ能力を職業別ノードへ置く場合も、このカタログの表現を使います。プレイヤーへ提示する役割、職業条件、配置構造の違いだけを理由に別の名称・アイコン・lore・タグを作りません。
- このカタログは地域や座標を管理しません。地域・配置構造の方針は [[35-skilltree]]、最終座標と edge は `structures/*.json` を正本とします。
