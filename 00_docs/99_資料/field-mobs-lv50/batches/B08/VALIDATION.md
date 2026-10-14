# B08 検証と統合後の受入

基点`3444b74b2`。重いMaven/dotnet/npm build、実server/client、疑似サーバー、DB接続、本番操作は行っていない。共有登録・POM・src/test・properties・schema・spawnerも変更していない。個別Mobの恒久JUnitを増やさず、filebase validatorと追加Javaのみの限定コンパイルを使う。

## 実行済みの軽量検証

- `validate_b08.py --repo-root .`: 全867 YAMLを二重キー拒否で解析。864 top-level master ID、共有status106/tag134に重複なし。8個体の継承・成長・2Lv刻み/幅6/最大50、通常近接、素材270/271/272の参照・drop継承、metadata、B08の所有範囲を確認。毒のSUPPORT_POWERと共通状態異常APIも確認する。`STATIC_RESULTS.json`に保存し、未登録2 skill IDは統合待ちとして明示する。
- `check_plugin_resources.py --repo-root .`: 成功、361 log ID/981 player message ID。直接logger/sendMessage/raw particle追加なし。
- JDK21.0.10/Paper1.21.11 API・既存workspaceのtarget/classesを参照し、B08 Java3ファイルのみの`javac -encoding UTF-8 -proc:none -Xlint:all`が成功、警告0。argfile/完全な出力はTempの`astralrecord-b08-javac/javac.args`と`javac-output.txt`に保持する。既存classesの基点を保証できないため、全体ソースbuildの代替ではない。PATHのOracle shimが応答しなかったため停止し、実在するJDK本体を直接使用した。
- Paperの`javap org.bukkit.Material`でSPIDER_SPAWN_EGG/SILVERFISH_SPAWN_EGG/POINTED_DRIPSTONE/RABBIT_HIDE/SANDの存在を確認した。
- 第1回独立Solレビュー: Java3/素材3/Mob2/manifest/validatorを既存Condition/MobRepository/Damage/Particle/MobSkillServiceと照合し、修正指摘なし。空の正規レビュー記録は作成していない。
- 第2回独立Solレビュー: 全13新規ファイルと既存AI/対象/状態/ダメージ処理を照合し、修正指摘なし。読み取り専用validator再実行も成功、変更13パスを確認。正規レビュー記録は作成していない。

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 00_docs/99_資料/field-mobs-lv50/batches/B08/validate_b08.py --repo-root .
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root .
```

Plugin testsから本番filebaseを読ませていない。src/test/POM/許可設計入力/テスト方針の変更がないためtraceability gateは対象外。既存データ契約内の追加で、params/schemaを変更しない。Plugin/API mapperと編集サイトの動的catalogが既存のlevels/ref/status形式を読むため、共通ソース変更は不要。編集サイトの画面反映は統合後に確認する。

## 実機受入・未検証

1. manifestの中央登録、既存destroy listenerへの追記、依存サービス停止前のstopを接続する。Java supportクラスも取り込む。8個体/3素材/2 skill IDのmaster読込、全体ID/変更参照、まとめた全体build/test、最終版番号を統合担当が確認する。
2. 正式なLv31以降の装備/class/skilltree構成でTTK/耐久を再試算する。現行代表R416.58では全DEFが完全防御となるため配置と戦闘受入を保留する。
3. client/Geyserで直線の毒針と扇の砂を見分け、横/扇外/背後/壁で避けられること、主対象以外に命中しないことを確認する。小型/大型petも確認する。
4. 毒は基礎70tick・20tick間隔の最大3パルス、盲目は基礎30tick。SUPPORT_POWER由来の毒、共通耐性/持続延長、同種更新、Shield命中、回避/無敵/0damage時の非付与、期限後の状態/表示消去を確認する。
5. 予告中の対象変更/死亡/ログアウト/召喚解除、帰還、caster死亡/despawn、world変更、行動不能、外部移動、Plugin停止、例外でtask/AI停止が残らないことを確認する。命中済み状態は既存の有限期限とclearAll/stop経路で消えることを確認する。
6. wall/chunk境界/WorldBorder/高低差でchunk loadと壁越しdamageがないことを確認する。地形・設置物・独自PotionEffectを残さない。
7. B09 mesa_vultureを含む三種・複数個体の再付与頻度と視界、通常ノックバック、DPS、素材売却期待値とGold sinkを実測する。連続命中による盲目更新が過剰にならない配置密度を決める。
8. 実在するメサworld/座標を確認してからspawnerを作る。本バッチはworld名・座標を捏造していない。

## Gitと保持

所有ファイルだけをローカルcommitし、branch/worktreeは統合待ちで保持する。develop merge/push/deploy/POM版番号更新は親統合担当。候補分類・staged差分・文字化け検査後にcommitする。worktree管理表の生成では削除を実行しない。
