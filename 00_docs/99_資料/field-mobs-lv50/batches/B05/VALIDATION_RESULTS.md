# B05 検証結果

対象branch `codex/field-mobs-lv50-b05`。共通基点 `e2058ade10a682118382e70aa01c7db758ac81c6`。作業日は2026-10-14 UTC。独占所有範囲のみ変更し、既存drop・table・spawner・中央登録・POM・properties・管理表に差分を作っていない。

## 軽量検証

Python 3.14/PyYAML 6.0.3、JDK21、ローカルMaven cacheのPaper API 1.21.11を使用した。dependency取得、Maven/dotnet/npm build、server/port/DB操作はしていない。

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 '<task-root>\00_docs\99_資料\field-mobs-lv50\batches\B05\verify_batch.py' `
  --paper-jar 'C:\Users\kaede\.m2\repository\io\papermc\paper\paper-api\1.21.11-R0.1-SNAPSHOT\paper-api-1.21.11-R0.1-SNAPSHOT.jar' `
  --javac 'C:\Program Files\Java\jdk-21.0.10\bin\javac.exe' --java 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
```

```text
PASS: 861 YAML parsed; 858 namespace IDs unique; 2 mobs/8 profiles/3 materials/2 manifests; owned diff only; 6 invalid fixtures rejected
PASS: 16 production geometry cases and 5 Paper Material enums
```

全40_filebase YAMLの重複キーと名前空間別ID、4レベル/幅6/最大30、stats/tagの共有カタログ、個体ごとの成長、素材の共通drop継承、35/35/8%×1、幸運・図鑑表示、item参照、Mob skill ID予約と登録manifest、所有パス、表示名・設計メタデータを検証。キノカブトは固有射撃だけで通常攻撃なし、狐は近接通常攻撃を維持することも明示して検査した。専用validatorへLv52・101%drop・未知paramsの異常fixtureを各種投入し、6件すべて拒否した。

本番の`hitDistance`/`insideSmoke`をそのまま抽出し、一時Javaクラスへ挿入してPaperの実際のBoundingBox/Vectorで検証した。正面・背後・射線外・重なり・射程をまたぐhitbox・全身射程外・高所・向きの回転・上下照準の9件、煙の中心・境界・境界外・矩形の隅・高さ上限・上階・下階の7件を確認。SILVERFISH_SPAWN_EGG/FOX_SPAWN_EGG/ARMADILLO_SCUTE/LEATHER/BUNDLEもPaper Materialで解決した。一時出力はworktree内99_workへ限定し、終了時に削除する。これはExecutor全体、Scheduler、Bukkitサーバーのコンパイル・動作確認ではない。個別Mob技の恒久JUnitは追加していない。

```powershell
& '<python>' -X utf8 '<task-root>\.codex\skills\astralrecord-code\scripts\check_plugin_resources.py' --repo-root '<task-root>'
```

```text
Plugin resource validation passed: 359 log IDs, 969 player message IDs.
```

上記の最終成功実行でstdout/stderrを確認し、警告なし。初回の専用validatorはGitの日本語パス引用を所有範囲外と判定したため、`core.quotepath=false`を検証コマンドへ明示して再実行・成功した。Pluginテストソース/POM/許可設計入力/テスト方針を変更していないため、恒久テストのトレーサビリティゲートは対象外。

## バランス事前計算

SPECの式と同格アンカー（Lv24: HP6000/R2090/D2174、26: 7100/2475/2572、28: 8300/2896/3007、30: 9500/3318/3442）を使用。Player命中92%、最終倍率130%、skill込みDPS1.6倍。敵の期待命中91.5%、会心0%、属性倍率100%、貫通・固定ダメージ・盾・一時補正なし。

| Lv | キノカブトTTK | コケギツネTTK | 散弾を全回受ける耐久 | 狐の通常+全pulse上限耐久 | 狐の予告停止を含む平均耐久 |
|---|---|---|---|---|---|
|24|5.37秒|5.34秒|12.93秒|9.18秒|11.49秒|
|26|5.36秒|5.34秒|12.93秒|9.18秒|11.50秒|
|28|5.36秒|5.33秒|12.93秒|9.18秒|11.50秒|
|30|5.35秒|5.32秒|12.93秒|9.17秒|11.49秒|

散弾の倍率/秒は`1.4/3`。狐の上限は`1/1.6 + 1.2/8`、予告で通常攻撃が止まる平均近似は`(1/1.6)*(6/8) + 1.2/8`。初回詠唱、通常攻撃の離散tick、移動、通常攻撃劣化、回避、根による拘束はこの平均式へ入れていない。森林3種構成の射撃・煙役として単独圧力を基準より抑えた。実際の重複被弾・視認性・TTK/耐久の確認は後続へ残す。

## 独立レビュー

第1回の独立Solは2Executor、2enemy、3material、サービス呼出経路・SPEC/manifestを照合。AR-CODE-001 [中]は理想距離6mで通常射程1.8mの攻撃が届かない点、AR-CODE-002 [低]は木色の予告を赤と案内した点、AR-CODE-003 [低]は一部内部メソッドのJavaDoc不足。担当へ委任された設計判断でキノカブトを既存射手と同じ固有技中心へ変更し、CD60tick・倍率140%に調整した。通常遠隔攻撃の距離だけ判定経路を使わないため、壁に隠れる対処を全射撃で維持できる。案内文とJavaDocも修正した。

第2回は修正担当と第1回担当から独立した別Solが現在版を照合し、新たな指摘なし。通常攻撃省略のRepository読込・MobCombatServiceのskip、散弾の方向固定・先頭衝突・重複防止、煙の範囲/壁/有効対象・0/20/40tick pulse、5tick中断・冪等cleanupと中央destroy/stop接続要求、資料・素材参照を確認した。JavaDocの追記は機能変更なし。

指摘があるため正規形式のレビュー記録をB05内に1件保存。通常のレビュー結果ディレクトリは今回のユーザー指定所有範囲外なので、B05内の保存指示を優先した。公式`update_review_record.py --fixed AR-CODE-001 AR-CODE-002 AR-CODE-003`で状態更新し、`validate_review_record.py`は成功。

## 統合担当へ残す事項

- `registration_manifest.yml`の2Executor登録、既存destroy callbackへclearCasterStateの合成、onDisableでScheduler一括cancel前のstop接続。
- Plugin全体コンパイル、`mvn -q test`、全新規Mob skill IDのRegistry解決と8profileのMobRepository/API読込。
- 実server/clientで照準・遮蔽・回避・階差・境界描画、煙の3秒と3pulse、全中断/死亡/despawn/停止経路、複数個体と召喚ペットを確認。
- ネッコガメとの同時戦闘で予告が埋もれず、拘束中の回避に余地があるかを確認。
- 実際の森林地域/worldを確認してからspawnerを作成。他batch統合後のID/参照を再検証。
- 装備担当が素材消費先を実装した後、用途・供給・売却効率を再確認。

中央未登録、全体build・実戦未検証のため、Plugin全体で実行できる状態を確認済みとは扱わない。push/develop統合/version/deployはしていない。worktreeを後続統合のため保持し、管理表は統合担当所有として本バッチでは更新しない。
