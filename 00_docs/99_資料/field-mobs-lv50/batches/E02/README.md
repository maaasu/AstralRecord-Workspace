# E02 既存フィールド雑魚への通常素材追加

- 基点: `2c419c1af8cff4ffdef63a8cf726f96a3408fe63`
- branch: `codex/field-mobs-existing-material-e02`
- worktree: `E:\AstralRecord-Workspace\99_work\field-mobs-existing-material-e02`
- 編集担当: メインエージェント1名。読取監査はGPT-6 Luna、独立レビューはGPT-6 Sol。
- 正本: `EXISTING_MATERIAL_ASSIGNMENTS.csv` のE02行、`EXISTING_MATERIAL_BATCHES.md`。
- 基点に `.agents/skills` は存在しないため、`.codex/skills` の統合入口・Git・master-data-author・code-reviewを参照。

## 所有範囲

| 対象 | 所有ファイル |
|:--|:--|
| グラスボア | `40_filebase/40.features.mob/enemy/v1.eriva_grassboar.yml` |
| 茸獣 | `40_filebase/40.features.mob/enemy/v1.eriva_sporecap_beast.yml` |
| 大草原の牙 | `40_filebase/10.features.item/10.material/v1.10a00302.eriva_grassboar_material.yml` |
| キノコの石づき | `40_filebase/10.features.item/10.material/v1.10a00303.eriva_sporecap_beast_material.yml` |
| 検証資料 | `00_docs/99_資料/field-mobs-lv50/batches/E02/` |

中央資料・割当CSV・管理表・共有loot・shop・recipe・装備・Plugin/API/Web/Editorは変更しない。基点と所有範囲は依頼を優先し、developへのrebase/統合、push、管理表更新、worktree削除は行わない。本番・DB・port操作とMaven/dotnet/npm buildは行わない。

## 素材と供給根拠

| Mob ID | 素材ID・名前 | progression | 適用レベル | icon |
|:--|:--|:--:|:--|:--|
| `eriva_grassboar` | `10a00302` 大草原の牙 | 15 | 15〜20 | `BONE` |
| `eriva_sporecap_beast` | `10a00303` キノコの石づき | 16 | 18（単一定義） | `BROWN_MUSHROOM` |

両素材はCOMMON、maxStack 64、saleValue 0、unTradeable false、unSellable false。通常素材の進行度は入手元Mobの先頭コメントと一致させた。未実装の製作先・売却収益をLoreに記載しない。用途の接続は後続統合担当の範囲とする。

直接 `drops.items` に `rate: 35.0 / amount: 1 / luckAffected: true / hidden: false` を1件追加。追加期待数量は基礎設定で1撃破あたり0.35個。既存通常素材35〜80%の下側とする割当設計に従い、既存dropの減量やloot weightの変更は行わない。実際の率はLUCKとDROP_RATE_INCREASEの影響を受け、0〜100へclampされる。

全カテゴリを含むアイコン監査で、`POINTED_DRIPSTONE` は既存スキルでも使用されていることを確認した。牙は動物由来の硬質組織であり、由来を直接示す `BONE` を採用する。既存の `10a00023`・`10a00056`・`10a00065` の骨片と同様、動物の硬い組織を示すためモチーフ上自然な再利用とした。`BROWN_MUSHROOM` は `10a00004` 灯茸の胞子で使用済みだが、今回の素材そのものが茸獣のキノコの根元であり、菌類を直接示すためモチーフ上自然な再利用とした。両Materialの存在は、pomが指定するPaper 1.21.11のローカルsources.jar内の `org/bukkit/Material.java` で確認した。

## 継承・抽選契約

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/repository/MobRepository.java:251` の `mergeLevelProfile` / `mergeJsonObjects` はmapを再帰mergeし、配列は置換する。
- グラスボアのLv.16〜20は `drops.exp` のみの上書き。Lv.15もitemsの上書きがない。rootへ追加したitemsを全profileが継承する。
- 茸獣はlevels配列を持たない単一Lv.18定義。rootへの追加が既定レベルへ適用される。
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobDropService.java:98` は各直接itemを独立抽選。率補正/clampは同ファイル:180。既存lootTableとの併用はEnemyスキーマに従う。

## 軽い検証

`validate_e02.py` は読取専用。Python 3.14 / PyYAML 6.0.3で実行し、`VALIDATION.json` に結果を保存した。

```powershell
$env:PYTHONIOENCODING = 'utf-8'
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '.\00_docs\99_資料\field-mobs-lv50\batches\E02\validate_e02.py'
```

worktreeルートで実行する。Python/PyYAMLが利用可能なら実行ファイルパスは環境に合わせてよい。

| 確認 | 結果 |
|:--|:--|
| 変更YAML4件のUTF-8解析・重複キー拒否 | PASS |
| 素材の共通/materialスキーマ、型・必須・列挙・ID形式 | PASS（Markdownスキーマに基づく対象フィールド検査） |
| 全858 YAMLのtop-level master ID走査 | 847個の異なるID、同一namespace内重複0 |
| 別namespace間の同名ID | 8件、全定義が指定基点から存在、新規重複0 |
| 新規素材参照と既存直接item・lootTable参照 | 全て解決 |
| 既存Mobのバイト比較 | 追加drop6行を除去すると基点と完全一致 |
| rootと全7レベルの実効データ比較 | 新素材以外の全フィールドが基点と一致 |
| 新素材の出現回数・率・個数・flags | 各レベルに1件、35.0%・1個・true/false |
| 変更範囲 | 所有4 YAMLと `batches/E02` のみ |
| `git diff --check` | PASS |

| Mob | レベル | 既存exp | 保持した既存item: rate / amount |
|:--|:--:|:--:|:--|
| `eriva_grassboar` | 15 | 140 | `10a00067`: 40.0 / 1~2、`10a00068`: 35.0 / 1 |
| `eriva_grassboar` | 16 | 160 | 同上 |
| `eriva_grassboar` | 17 | 180 | 同上 |
| `eriva_grassboar` | 18 | 200 | 同上 |
| `eriva_grassboar` | 19 | 220 | 同上 |
| `eriva_grassboar` | 20 | 240 | 同上 |
| `eriva_sporecap_beast` | 18 | 200 | `10a00100`: 5.0 / 1 |

既存直接itemは全てluckAffected true / hidden false。双方の `loot_table:normal_enemy_common_table_tier_2` を保持。money・stats・ai・skill・spawn・level・name・loreなど既存の全フィールドも不変。別namespace同名8件の詳細と全レベルの実効dropは `VALIDATION.json` に記録。

## 独立レビュー

GPT-6 Solによる読み取り専用レビューを実施した。2敵の全7レベルを独立比較し、既存フィールド保持と新素材1件追加を確認。修正版のvalidatorと `git diff --check` もPASS。

アイコン使用状況の誤記1件（`AR-CODE-001`）を修正し、レビュー記録 `[完了] 26-10-15 01：08：09 code-review.md` を本ディレクトリに保存した。通常のレビュー記録保存先よりも、依頼の `batches/E02` だけを編集する制限を優先した。`update_review_record.py --fixed AR-CODE-001` と `validate_review_record.py` がPASS。独立レビューの状態も本節へ反映した。未解決指摘・未確認質問はなし。

## 引継ぎ

既存マスタ契約内のデータ追加であり、読込形式・変換・スキーマ契約は変更しない。Plugin/API/Resource Packや編集サイトのコード更新は不要。後続統合担当は最新統合ツリーでIDと変更参照を再検証する。独立レビューとローカルcommit後もbranch/worktreeを保持する。
