# E03 既存フィールド雑魚の通常素材追加

基点: `2c419c1af8cff4ffdef63a8cf726f96a3408fe63`。予約の正本は `EXISTING_MATERIAL_ASSIGNMENTS.csv`、実装条件は `EXISTING_MATERIAL_BATCHES.md`。`.agents/skills` は存在せず、実在する `.codex/skills` のカタログ、統合入口、master-data-author、git-worktree-develop、code-reviewを使用した。

| Mob ID | 既存実効レベル | 素材ID / 表示名 | progression | icon |
|:--|:--|:--|:--|:--|
| `eriva_starhorn_guardian` | 15 | `10a00304` 星角のかけら | 15 | `GOAT_HORN` |
| `eriva_thornfang_wolf` | 17 | `10a00305` トゲ牙 | 16 | `POINTED_DRIPSTONE` |

両Mobとも `levels` を持たない従来形式。各rootの `drops.items` 末尾へ指定素材を1件追加し、既存 `item:10a00100` の5%・1個・幸運補正・表示設定、exp、lootTableおよび全フィールドを維持した。未登録のレベル指定でも既存のレベル解決規則を維持し、追加のレベル定義は作らない。

## 所有範囲

- `40_filebase/40.features.mob/enemy/v1.eriva_starhorn_guardian.yml`
- `40_filebase/40.features.mob/enemy/v1.eriva_thornfang_wolf.yml`
- `40_filebase/10.features.item/10.material/v1.10a00304.eriva_starhorn_guardian_material.yml`
- `40_filebase/10.features.item/10.material/v1.10a00305.eriva_thornfang_wolf_material.yml`
- 本 `batches/E03/` の根拠・検証資料

他バッチ、中央資料、共有loot、ショップ、装備、recipe、schema、API、Web、Editor、作業ツリー管理表は編集しない。共有ファイルの更新と統合順序は親・統合担当に委ねる。この2素材の参照は本バッチ内で解決し、他バッチへの依存はない。

## 値と表示の根拠

通常素材は指定どおり `COMMON`、設定率35%、1個、`luckAffected: true`、`hidden: false`。無補正時の追加期待数量は各0.35個/撃破。直接dropはMobDropServiceで各項目を独立抽選し、LUCKとDROP_RATE_INCREASEの補正後に0〜100%へ収める。既存dropやlootTableの抽選枠を減らさない。

素材のprogressionは各Mobの設計コメントと同値。最大スタック64、売値0、取引・売却禁止フラグはfalseとし、新たな金策収入は追加しない。Loreは角や牙の由来・外観だけとし、製作先は未実装のまま扱う。素材の利用先追加は今回の所有範囲外。

全filebaseの既存icon使用状況を確認した。`GOAT_HORN` は `v1.swordsman_challenging_roar.yml`、`POINTED_DRIPSTONE` は `v1.sharpshooter_piercing_line.yml` で使用済みだが、素材では未使用。前者は角そのもの、後者は尖った牙の輪郭を表すため、モチーフが明確な再利用として採用した。customModelData・外部テクスチャを使わないためResource Pack更新は不要。

PluginのPOMが指定するPaper API `1.21.11-R0.1-SNAPSHOT` のローカルjar `paper-api-1.21.11-R0.1-20260228.115254-85.jar` 内の `org/bukkit/Material.class` に両icon名が存在することを確認した。依存解決・ダウンロード・buildは行っていない。

既存のitemスキーマとmaterialスキーマだけを使用。PluginのItemRepositoryが既存共通フィールドを読み、APIのItemRepositoryがmaterialカテゴリを扱い、filebase/config.ymlが番号付き素材ディレクトリとitem参照を既に解決する。読込契約・Editorの変更は不要。

## 軽量検証

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '00_docs\99_資料\field-mobs-lv50\batches\E03\validate.py'
git diff --check
```

`validate.py` はPyYAMLを使用し、結果を同ディレクトリの `validation.json` へ出力する。

- 全 `40_filebase/**/*.yml` の構文解析と全トップレベルIDの走査。
- 変更YAMLの重複キー検査、素材の必須キー・型・カテゴリ・命名・値・表示名・設計コメントをスキーマと照合。
- 追加2 IDが基点では未使用で現在は各1件、追加参照と既存直接drop・lootTable参照が解決可能。
- rootと全実効レベルで新素材が末尾にちょうど1件。新素材を除去したオブジェクトは基点と完全一致。
- 全体の同名ID8種はskill/buffまたはfishing_area/worldをまたぐ基点からの既存値。今回の追加で増減なし。
- Git差分が所有4 YAMLと `batches/E03/` に限定されること。

重いMaven/dotnet/npm build、server/本番DB/port操作、push、rebase、develop統合、deployは行わない。branch/worktreeは親担当への引継ぎのため保持する。

## 独立レビュー

差分作成後にGPT-6 Solの読み取り専用担当2名が独立に確認した。

- 実装レビュー: CSV・スキーマ・4 YAML・MobDropService・所有範囲・表示と素材進行度を照合。指摘なし、未解決質問なし。
- データ整合性レビュー: 検証スクリプトと記録の内容を読み、別のPython照合で858 YAML、847 ID、基点からの既存同名ID8種、実効Lv15/17、追加dropを除いた既存値の完全一致を確認。指摘なし、未解決質問なし。

指摘がないためcode-reviewの正規レビュー記録は作成していない。コミット候補監査も対象7ファイルだけで、生成物・他作業の混入はない。
