# AstralRecord レビュー記録
- フォーマット版: `1`
- 使用スキル: `code-review`
- 対象パス: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java`
- 作成日時: `2026-08-01T22:19:50+09:00`
- 完了状態: `完了`
- 指摘修正数 / 指摘数: `3 / 3`

## 指摘一覧

### AR-CODE-001 [中] action ring 終了時に後続の title まで消去する
- 種別: `バグ/アルゴリズム`
- 対象: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java:850`
- 関連箇所: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/world/service/WorldService.java:192`
- 根拠: action ring の案内は `Title.Times` の stay を 1 日に設定して session 終了まで残す一方、`destroy()` は無条件に `Player#resetTitle()` を呼び出す。world change 等の既存機能も同じ `Player#showTitle()` を利用しており、title の所有者を識別・復元する仕組みはない。
- 問題: ring 表示中に別機能が title を表示するとその title は action ring の案内を置換するが、後から ring を閉じると `resetTitle()` が別機能の表示まで消去する。
- 影響: world/region 遷移などで表示すべき title が、ring を閉じた時点で早期に消える。選択確定後は session が無期限に残るため衝突可能な時間も無期限になる。
- 修正方針: action ring 専用の title 表示状態を共有 UI 層で管理し、ring の終了では自身が最後に表示した案内だけを解除する。少なくとも他機能が title を更新した後に action ring が終了しても、その title を reset しない契約へ変更する。
- 修正対象候補: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java`
- 修正可否: `要確認`
- 確信度: `高`
- 修正状態: `修正済み`

### AR-CODE-002 [低] 表示ライフサイクルの回帰を検出するテストがない
- 種別: `テスト不足`
- 対象: `10_plugin/AstralRecord/src/test/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingServiceTest.java:52`
- 関連箇所: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java:618`
- 根拠: 追加テストは `PlayerMsgResource` から取得する2つの文字列と色コードだけを検証する。設計書 `00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md` が定める、開始時の空 title/subtitle、選択確定後の自動終了停止・残り時間バー非表示、終了時の表示解除は検証していない。
- 問題: `showInstruction` の呼出位置、`WAITING_CAST` の無期限維持、timer label の消去・更新停止が変わっても、今回追加されたテストは成功する。
- 影響: 利用者に見える表示・操作フローの退行を自動検出できない。
- 修正方針: MockBukkit 等で action ring を開き、選択確定後に旧 timeout を超えて session が維持されること、timer label が空になること、title/subtitle が指定 Component で表示・終了時に解除されることを検証するテストを追加する。
- 修正対象候補: `10_plugin/AstralRecord/src/test/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingServiceTest.java`
- 修正可否: `自動修正可`
- 確信度: `高`
- 修正状態: `修正済み`

### AR-CODE-003 [中] 選択確定後に発動不能となっても ring を閉じる
- 種別: `仕様不整合`
- 対象: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java:197`
- 関連箇所: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java:659, 10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillService.java:382`
- 根拠: `refreshSlotAvailability()` は `SELECTING` 中だけ実行され、確定後の `WAITING_CAST` では選択時点の可否を保持する。一方、左クリック時は session を先に remove/destroy してから `SkillService.castSkill()` を呼び、同 service は現在の resource・cooldown 等を再検証する。設計書は選択確定後の session を「左クリック発動または明示的な終了まで」維持すると定める。
- 問題: 確定後に resource 不足や cooldown などで発動不可になった場合、`castSkill` は失敗するが session と subtitle は既に破棄されている。
- 影響: 自動終了を廃止した後、待機中に状態が変化したプレイヤーは発動に失敗しても再選択・明示終了を行えず、仕様どおりの操作を継続できない。
- 修正方針: 左クリック直前に確定 skill の可否を再検証し、失敗時は session を維持して可否表示を更新する。発動失敗でも閉じる仕様を採る場合は、設計書で明示的に定義する。
- 修正対象候補: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java`
- 修正可否: `要確認`
- 確信度: `高`
- 修正状態: `修正済み`

## 未確認/質問

なし。

## 修正スキル入力サマリ
- 自動修正候補: `なし`
- 要確認: `なし`
- 推奨修正順: `なし`
- 対象範囲: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java`

## 確認した範囲
- 対象領域: AstralRecord Minecraft Plugin の action ring 表示・入力状態・player message resource
- 読んだルール/設計書: `AGENTS.md`, `README.md`, `PLUGIN_GUIDE.md`, `.codex/skills/astralrecord-code/references/plugin-code.md`, `00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md`, `00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-統合フロー.md`
- 読んだソース: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingService.java`, `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillService.java`, `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/PlayerMsgId.java`, `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/PlayerMsgResource.java`, `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/world/service/WorldService.java`, `10_plugin/AstralRecord/src/main/resources/player.properties`, `10_plugin/AstralRecord/src/test/java/io/github/maaasu/astralRecord/feature/skill/service/SkillActionRingServiceTest.java`
- 実行した検査: `git status --short --branch`（対象5ファイルの未コミット差分を確認）、`git diff`、`rg` による呼出箇所照合、`python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root .`（PASS）、`python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py`（PASS）、`mvn -Dtest=SkillActionRingServiceTest test`（64秒でtimeout）

## 対象外
- 実サーバーでの表示確認、実装・テスト・resource・設計書の編集。前者はレビュー環境外、後者は独立レビューの禁止対象。
