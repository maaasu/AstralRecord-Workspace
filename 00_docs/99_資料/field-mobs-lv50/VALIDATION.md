# スキル基盤と共通検証

調査基点bc7877ae3。個別Mobスキルはastralrecord-code/SKILL.mdとreferences/plugin-code.mdが主担当。astralrecord-skill-authorは職業・プレイヤースキルを前提とするため今回は流用しない。

- 基盤: 10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/skill/{MobSkillExecutor,MobSkillContext,MobSkillRegistry,MobSkillBinding}.java。
- feature/mob/service/MobSkillService.javaがID検索、詠唱、CD、射程、対象判定を管理。
- MobSkillExecutorの契約: 一意のmob_ ID、displayName/defaultTiming/validate/cast。bindingはtiming上書きと最大10個の数値params。
- 登録中央: AstralRecord.javaのmobSkillRegistry.register(new ...MobSkillExecutor(...))付近(基点で約1981行)。統合担当のみ編集。
- YAML: ai.combat.skills[]のobject形式(id/activationRange/cooldownTicks/castTimeTicks/params)。文字列配列は互換用なので新規では使わない。
- 既存参考: feature/mob/skill/eriva/ErivaThornfangPounceMobSkillExecutor.java、enemy/v1.eriva_thornfang_wolf.yml。
- 迷彩ゾンビとeriva_grassboarはai.combat.skillsなし、通常攻撃のみ。既存種の素材追加バッチにスキル修正を混ぜない。
- ParticleDisplayService/SharedParticleDefinitions等既存共通表現を使う。新規状態の死亡・despawn・target消失・world変更・plugin停止時の解除を担当が確認。
- 現行YAMLで不足する挙動は未対応paramsを捏造せず、executor実装または共有基盤の最小変更案として統合担当へ渡す。

## 検証順

1. 変更YAMLをparseしてカテゴリschemaを再読。motif/progression、ID/日本語名/icon、最大level50と種内差4〜10、素材入手量、参照を確認。
2. 全40_filebase YAMLのID重複と変更参照を確認。カテゴリ横断同名を一律衝突とせず、schemaが定めるmaster namespaceごとに判定。itemは固定8桁ID。
3. モブskillsのmob_ IDをexecutor登録manifestと照合。登録後はregistryで全件解決可能か確認。未登録は完成扱いしない。
4. Plugin source/resourcesが変わる時: python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <task-worktree>。
5. Plugin通常test: Pluginディレクトリでmvn -q test。重いbuild/testは全作業で直列にする。
6. Plugin docs/POM/test等ゲート対象変更時だけ: python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py (実行前に引数をskill正本で確認)。個別Mob技の恒久JUnitは追加しない。共有不変条件変更は適切な共通テスト。
7. 既存ドロップ率・個数・lootTableの保持をdiffで確認。Lv15グラスボアは10a00067 40%、10a00068 35%。Lv1グラスボアは肉80%/毛皮35%。Lv7森オオカミは通常45〜55%/希少3〜5%。新規初期値35%/10%/1%は最終確定値ではなく個別調整対象。
8. 専用Mob YAML validator CLIは調査で未確認。実在しない検証コマンドは作らない。Master Data Editor保存前検証/Plugin読込照合が使える場合も、server/portを起動する作業は今回禁止。
9. 実戦の予兆・命中・回避・複数個体の共存・TTK・耐久確認は後続許可後の実server/client確認。今回未実施を明記。
10. 限定diff、文字化けcheck、独立レビュー、必要修正後限定commit。push/develop merge/version/deployは今回実施しない。

既存tier_2共通tableはprogression15のpool率0.005〜12%を含む。新規高レベル雑魚へ無条件コピーせず、専用drop/lootを設計する。既存用途: 10a00067/68は装備transcendence.requiredMaterialsで消費、10a00001/02はshop/recipe参照。dropは用途判定の消費先に含めない。

スポナー補足: マスタYAMLに設置座標は含まれず、実行環境mob_spawners.ymlに保存される。配置条件未決定のspawner量産は禁止。新規種のspawner定義と実設置を分け、未確認地域の設定は作成を保留する。未定義level参照は最小levelへfallbackするため、検証で黙って通さずエラーとして発見する。
