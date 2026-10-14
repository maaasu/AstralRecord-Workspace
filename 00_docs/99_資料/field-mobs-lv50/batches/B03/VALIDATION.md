# B03 検証結果

基点 `e2058ade10a682118382e70aa01c7db758ac81c6`。B03専用worktreeで検証。実装者とは異なるSolによる独立レビューを行う。重いMaven/dotnet/npm build/test、server、DB、port、push、develop統合、版番号更新は実施しない。

| 検証 | 結果 | 範囲・限界 |
|---|---|---|
| validate_b03.py | PASS | 全861 YAML parse、858 top-level master IDのnamespace重複なし。5新規YAMLの重複キー/既存スキーマ契約の主要必須項目/status/tag/素材参照/level/skill manifest整合 |
| 実Executor形状診断 | PASS 16件 | 前方・横回避・背後・射程外・重なり・高いhitbox・逆方向・入力保全を、実メソッドへ呼出し。Bukkitサーバーなし/本番マスタfixtureなし |
| javac 21 | PASS、出力なし・警告なし | 2 ExecutorとB03GeometryCheckだけ。既存メインtarget/classesとキャッシュPaper 1.21.11 APIに対する限定コンパイル。全基点のソースbuildではない |
| check_plugin_resources.py | PASS | 359 log IDs、969 player message IDs。新規property/LogId/MsgId/直接メッセージ送信なし |
| スキーマ手読 | PASS | mob/enemy/item/material正本、levels再帰Map継承・baseStats配列全置換、通常攻撃/skill分離に照合。専用公式Mob validatorの代用とはしない |
| 既存drop保持 | PASS | 新規5YAMLだけ追加。既存敵/loot/spawnerへの差分なし |
| 第1回Sol独立レビュー | 修正必須指摘なし | cast/CD/対象・AI/予告・壁前の拡張hitbox/cleanup/同一instance登録・停止要求を照合。成果物や記録の編集なし |
| 第2回Sol独立レビュー | 2指摘を修正 | ratio前Rによる防御比へ試算修正、同期反撃後のcaster再確認を追加。第1回レビューとは別担当。重いbuild禁止を伝達 |
| 修正の独立確認 | 2件解消・回帰なし | 修正担当とは別のSolが実DamageCalculator/反撃経路へ再照合し、静的validatorも再実行した |
| PaperのMaterial照合 | PASS | javapでSHEEP_SPAWN_EGG、SILVERFISH_SPAWN_EGG、WHITE_WOOL、GOAT_HORN、SHEARSの5点を実APIから確認 |

変更のない既存ファイルは再保存していない。B03所有外の中央資料、管理表、Registry、AstralRecord.java、共有基盤、POM、properties、既存loot、spawnerには差分を作らない。

レビュー2件の詳細と修正状態は同じB03内の[正規レビュー記録]([完了]%2026-10-15%2000：42：09%20code-review.md)に保存。ユーザー指定のバッチ所有範囲を優先し、通常のレビュー結果ディレクトリは編集しない。記録はvalidate_review_record.pyとupdate_review_record.pyで検証・更新した。作業ツリー管理表も今回の中央編集禁止を優先して未更新とし、worktreeはローカルコミットを保持して統合担当へ返す。

## 再実行

作業rootから、実体のPythonへ次を渡す（WindowsAppsのpython stubは起動不可のため使用しない）。

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '00_docs/99_資料/field-mobs-lv50/batches/B03/validate_b03.py'
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '.codex/skills/astralrecord-code/scripts/check_plugin_resources.py' --repo-root <task-root>
& './00_docs/99_資料/field-mobs-lv50/batches/B03/verify_geometry.ps1'
```

verify_geometry.ps1は既存class/cacheを指定可能。成果物はTemp内のみで、stdout/stderr全量をjavac-output.txt/geometry-output.txtへ保存する。通常src/test/POM/許可設計入力への変更はないためPlugin恒久テストのトレーサビリティゲートは非該当。B03診断はこのバッチだけの検証資料であり、個別コンテンツの恒久JUnitに登録しない。

## 理論試算

[SPEC.md](SPEC.md)の非会心・一時補正なしのPlayerアンカー条件を使用する。Lv32超は標準Player/Mob曲線も暫定。通常攻撃＋skill期待DPS×1.6によるPlayerTTKと、Mobの技を通常攻撃に追加し続ける保守的な耐久下限を出す。

| Mob / Lv | TTK秒 | 通常攻撃だけの耐久秒 | 技を加算した耐久下限秒 |
|---|---:|---:|---:|
| 羊28 | 6.90 | 10.66 | 7.80 |
| 羊30 | 6.89 | 10.66 | 7.80 |
| 羊32 | 6.89 | 10.66 | 7.80 |
| 羊34 | 6.88 | 10.67 | 7.80 |
| カマキリ42 | 5.14 | 8.26 | 6.48 |
| カマキリ44 | 5.14 | 8.26 | 6.48 |
| カマキリ46 | 5.14 | 8.26 | 6.48 |
| カマキリ48 | 5.14 | 8.26 | 6.48 |

予告/詠唱中に通常攻撃が停止する時間をCD周期の羊24/100、カマキリ32/120として単純に引いた平均DPSモデルでは、耐久は羊約9.45秒・カマキリ約8.19秒となる。通常攻撃の位相/再開遅延、初撃からの有限戦闘、回避は未計測なので実戦測定を代替しない。高位の最大値は羊よりHP1.4倍/攻撃1.9倍/防御1.5倍で、Lv42〜48をLv28〜34と同じ実数にしていない。羊はやや長いTTK、カマキリは短いTTKと扇形への対処を持つ。

## 統合待ち・未検証

- 2 implementationIdは中央未登録。REGISTRATION.jsonの完全修飾class・依存4点・登録コード案・destroy/stop配線を統合担当で適用する。同一instanceを保持し、既存destroy callbackを維持する。停止前のstop()呼出しが必要。
- 素材用途は装備担当が接続する。通常/希少素材率、売却収入、Lv30超EXP効率は全体経済の実測待ち。
- 平原B/Cの具体的位置とworld対応を確認するまで、新規spawner/座標追加は保留。
- 基点ソース全体のMaven test/build、Registry読込、実server/clientでの予告/命中/横回避/背後/壁/高低差/複数個体/ペット/管理モード/死亡/despawn/leash/world変更/停止/TTKを統合後に検証する。
