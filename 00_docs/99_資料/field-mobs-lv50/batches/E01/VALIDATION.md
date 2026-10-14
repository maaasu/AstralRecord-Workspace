# E01 既存フィールド雑魚2種の素材追加

基点: `2c419c1af8cff4ffdef63a8cf726f96a3408fe63`

branch: `codex/field-mobs-existing-material-e01`

worktree: `E:\AstralRecord-Workspace\99_work\field-mobs-existing-material-e01`

## 所有範囲

| 既存Mob ID | 追加素材 | 素材ID | 既存レベル |
|:--|:--|:--|:--|
| `eriva_bark_sentinel` | 樹皮の板 | `10a00300` | 18 |
| `eriva_camouflage_zombie` | 草まみれの布 | `10a00301` | 18 / 20 / 22 / 24 / 26 / 28 / 30 |

変更は `EXISTING_MATERIAL_ASSIGNMENTS.csv` のE01の `enemy_path` 2件、`material_path` 2件、本 `batches/E01/` の検証資料だけ。中央資料・登録・共有loot・ショップ・管理表は編集しない。新規Mob・スキルは作成しない。他担当の素材への依存はない。後続統合担当がこのcommitを取り込み、統合先でID・参照を再検証する。

## 採用値と根拠

- 両素材は `COMMON`、`rate: 35.0`、`amount: 1`、`luckAffected: true`、`hidden: false`。CSVと `EXISTING_MATERIAL_BATCHES.md` の案をそのまま採用。補正前の追加期待数量は1撃破あたり0.35個。実効確率は既存の幸運・ドロップ増加率で変わる。
- Enemyスキーマは直接 `drops.items` と `drops.lootTable` の独立抽選を許容する。既存抽選の候補やweightを変更せず、新素材を直接itemsへ追記する。
- 樹皮の番兵の `item:10a00100`（森の樹脂、5.0%、1個、幸運適用、表示あり）と、両Mobの `normal_enemy_common_table_tier_2` は保持。既存経験値・ステータス・AI・スキル・装備・レベルも保持する。
- 通常素材のprogressionは入手元と同値の16。モチーフは樹皮と草の付いた布に絞り、指定された平易な素材名を保持する。
- `OAK_PLANKS` は板の形状、`GREEN_WOOL` は草の付いた布を示す標準Material。既存itemのicon使用を確認した。Resource PackやcustomModelDataは不要。
- `maxStack: 64`、`saleValue: 0`。用途は未実装というバッチ仕様に従い、Loreに製作先を断言せず、今回の範囲でrecipe・ショップ・金策導線は作らない。

## レベル継承と既存データ保持

`10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/repository/MobRepository.java` の `mergeLevelProfile` / `mergeJsonObjects` はMapを再帰mergeし、配列を置換する。樹皮の番兵は単一レベル。迷彩ゾンビの各profileには `drops.items` がなく、Lv.20以降は `drops.exp` だけを上書きする。そのため共通 `drops.items` へ1件追加すれば、全7profileが同じ新素材を継承する。

`validate_e01.py` はこのmerge規則で基点と変更後の実効データを作り、新素材がちょうど1件であることを確認する。その1件を取り除いた実効Mob全体を基点と比較し、items・率・個数・幸運・hidden・lootTableだけでなく、その他すべての既存フィールドも同値と確認する。生YAMLの解析データも同じ方法で比較する。

| Mob | Lv. | 既存経験値 | 新素材件数 | 設定率・個数 | その他の実効データ |
|:--|--:|--:|--:|:--|:--|
| 樹皮の番兵 | 18 | 200 | 1 | 35%・1個 | 基点と同値 |
| 迷彩ゾンビ | 18 | 200 | 1 | 35%・1個 | 基点と同値 |
| 迷彩ゾンビ | 20 | 240 | 1 | 35%・1個 | 基点と同値 |
| 迷彩ゾンビ | 22 | 280 | 1 | 35%・1個 | 基点と同値 |
| 迷彩ゾンビ | 24 | 320 | 1 | 35%・1個 | 基点と同値 |
| 迷彩ゾンビ | 26 | 360 | 1 | 35%・1個 | 基点と同値 |
| 迷彩ゾンビ | 28 | 400 | 1 | 35%・1個 | 基点と同値 |
| 迷彩ゾンビ | 30 | 440 | 1 | 35%・1個 | 基点と同値 |

## 検証と再実行

依存はPythonとPyYAML（実行環境: Python 3.14 / PyYAML 6.0.3）。buildや外部通信は行わない。

```powershell
python 00_docs/99_資料/field-mobs-lv50/batches/E01/validate_e01.py
git diff --check
```

この端末ではWindowsAppsのaliasではなく、`C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe` で実行した。出力は `VALIDATION.json` に保存する。

- 全858 `40_filebase/**/*.yml` のYAML解析・Mapキー重複検出: PASS。
- 論理マスタのトップレベルIDを全体走査（847種類）: 新規ID重複なし、同namespace内の重複なし。既存のskill/buff 4組とfishing area/world 4組の同名IDは基点の各定義と照合し、今回導入された重複ではないと確認。結果JSONに一覧を残す。
- 新素材2件のID・path・名前・COMMON・型・キー・値・designメタデータ: CSV、Item/Material Markdownスキーマに適合。
- 新drop2件の参照形式・rate・amount・boolean: Enemy Markdownスキーマに適合。専用JSON Schemaはないため、変更項目の明示検査と手動照合を行った。
- 既存dropを含む各実効profileのitem参照とlootTable参照: 解決先が各1件。
- 全8実効profile: 新素材が1件、その他実効データが基点と同値。
- Git差分と未追跡ファイル: E01所有範囲内。
- 独立レビュー（GPT-6 Sol）: 4YAMLと検証scriptを確認・再実行、指摘なし。指摘のない正規レビュー記録は作成しない。
- 別観点の機械確認（GPT-6 Luna）: 全8profile・参照・所有範囲を再確認。検証scriptの報告表現とCSV行数検査の補強提案に従い、独自契約検査/手動スキーマ照合と明記し、2行ちょうどの期待Mob/素材/path集合を検査するよう修正した。
- 修正後の独立再確認（GPT-6 Sol）: 再実行結果と保存JSONが完全一致、検証資料の範囲・限界も一致、未解決指摘なし。

読んだ正本はルート/`40_filebase`のAGENTS、Filebase READMEとitem/material/mob/enemy設計・各Markdownスキーマ、モチーフ選定ガイド、作成時チェックリスト、戦闘バランスREADMEと既存エリヴァ報酬の関連記述、CSVとバッチ仕様。基点には `.agents/skills/` がないため、実在する `.codex/skills/README.md` と統合入口・Git・master-data-author・code-reviewのSKILL/必要参照を使用した。

## 実行上の限界

サーバでの実ロード・実抽選・図鑑表示は未確認。依頼どおりMaven/dotnet/npm等の重いbuild、本番・DB・ポート操作を行わない。push、developへのrebase/統合、作業ツリー管理表更新は行わず、ローカルcommitと専用worktreeを後続統合用に保持する。
