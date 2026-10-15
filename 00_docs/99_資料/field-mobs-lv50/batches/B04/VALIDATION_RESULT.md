# B04 検証結果

専用worktree: `E:\AstralRecord-Workspace\99_work\field-mobs-lv50-b04`。重いMaven/dotnet/npm build、サーバー起動、DB・本番・ポート操作は行わない。

## 実施した検証

| 検証 | 結果と範囲 |
|---|---|
| YAML parse | 全862件のfilebase YAMLを解析。Map内の重複キーも検査 |
| ID重複 | top-level master IDをnamespaceごとに全件走査し、重複なし。共有status/tagカタログは今回編集しない |
| 変更参照 | 6件YAMLのitem参照、status/tag、skill IDとmanifest、通常攻撃／直接dropを確認 |
| レベル | 2種とも42,44,46,48、幅6、既定42、上限50以下、全個体のステータス／EXPを確認 |
| schema | mob/enemy/item/material正本を再読。未知のparams、player skill master、偽の土／風属性は追加しない |
| 既存drop保持 | 新規ファイルのみ。既存enemy・lootの率／数量／table変更なし |
| 限定Java compile | B04の実Executor 2件とGeometryProbeだけをJDK21でjavac。既存target/classesとPaper API 1.21.11を読取使用し成功、警告なし |
| 地形診断 | 実Executorのprivate `clearStep` を反射経由で呼び、平面／薄壁／崖／液体／実体幅の境界／未読込chunk／低天井／空中と地上の支持差／slab／狭い縁の24assertを確認。Paper Material 6件も確認。サーバーは起動しない |
| resources | `check_plugin_resources.py --repo-root <worktree>` 成功。359 log IDs / 969 player message IDs |
| 独立レビュー | 第1回Solレビュー2件（例外準備cleanup、重力復元順）を修正。修正担当と異なるSolによる第2回レビューで新規指摘なし。状態更新ツールによる記録は本ディレクトリの `[完了] ... code-review.md` |

`GeometryProbe.java` はこのバッチ引き渡しの診断資料で、Plugin src/testやJUnitへ追加しない。疑似サーバー・MockBukkit・Maven依存を使わない。テストのトレーサビリティゲート対象（Plugin src/test、POM、許可設計入力／テスト方針）に差分がないため、そのゲートは起動しない。

## 再実行

利用できるPython（PyYAML）で本worktreeのrootから実行する。`python` のWindows Store aliasが実行不能だったため、この環境では実体 `C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe` を使用した。

```powershell
python 00_docs/99_資料/field-mobs-lv50/batches/B04/validate_batch.py
python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <worktree>
python 00_docs/99_資料/field-mobs-lv50/batches/B04/run_geometry_probe.py --baseline-classes <既存Plugin-target/classes> --jdk <JDK21-root> --m2 <Maven-cache-root> --temp-root <書込可能なTemp>
```

限定javacは既存クラスとのAPI整合と今回の構文を確認するが、全ソースの再コンパイルを保証しない。cacheが存在しない環境では、統合後の直列buildへ渡す。生成classは指定Tempへ保存するだけで、Plugin出力や本番配布先へ書き込まない。

## 未実施と中央統合要求

- 2つのExecutorの中央登録とregistry解決。manifestでは両方 `registered: false`。登録前は動作が有効になっていない。
- 統合後の全体compile／通常test。重いbuildは親が一度に一件だけ実施する。
- 実server/clientで予告の視認性、回避と命中、通常攻撃への復帰、死亡／帰還／行動不能／target喪失／World変更／Plugin停止、複数個体共存を確認する。地形診断は実戦や全ライフサイクル動作を実行した証明ではない。
- Lv40帯の装備込みPlayer性能からTTK／耐久／被弾時の固有技を再計算する。Lv31以上の暫定アンカーのまま実配置しない。
- 平原Cのworld内区域、座標、densityを確認してから統合担当がスポナーを決める。今回、座標／配置を作らない。
- version更新、worktree管理表更新、push、developへのrebase／mergeは依頼の所有範囲指定に従って未実施。専用branch/worktreeを保持する。

最終の軽量検査出力は `STATIC_CHECKS.log.txt`。既知の未登録・全体build未実施・実配置保留を警告なく成功したことに置き換えない。
