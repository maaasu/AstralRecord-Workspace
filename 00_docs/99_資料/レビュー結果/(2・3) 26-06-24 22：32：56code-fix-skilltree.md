# SkillTree コード修正結果

- 対象レビュー: `00_docs/99_資料/レビュー結果/(0・3) 26-06-24 22：15：31code-review-skilltree.md`
- 対象パス: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skilltree`
- skill: `$astralrecord-code-fix`
- 指摘修正数 / 指摘数: `2 / 3`
- 完了状態: 未完了
- 実施日: 2026-06-24

## 指摘一覧

### AR-CODE-001 [高] 未ロードのスキルツリー進行を空状態として扱い、所持スキルとステータス補正が一時的に消える
- 修正状態: 未対応
- 修正可否: 要確認
- 理由: レビュー結果の `Q-CODE-001` で、初回ロード中の通常スキル発動・ステータス計算を保留するか、スキルツリー由来分だけ未適用で進めるかの仕様確認が必要とされているため。

### AR-CODE-002 [中] skilltree_structure.yml の保存失敗を握りつぶし、成功扱いで dirty を解除している
- 修正状態: 修正済み
- 修正可否: 自動修正可
- 変更内容: `SkillTreeStructureRepository.save()` が保存失敗を例外として上位へ伝えるようにした。`SkillTreeService` は構造保存に失敗した場合、`E_9004` で Throwable 付きログを出し、`structureDirty` を維持して次回保存で再試行する。
- 変更ファイル: `SkillTreeStructureRepository.java`, `SkillTreeService.java`, `LogId.java`, `logger.properties`

### AR-CODE-003 [低] 未使用の hotbar 操作メソッドが残り、設計済みの非 hotbar 方針と読み手を混乱させる
- 修正状態: 修正済み
- 修正可否: 自動修正可
- 変更内容: 空実装の `applySkillTreeHotbar()` / `renderSkillTreeHotbar()` と常時 false の hotbar 判定 API を削除した。実際の処理に合わせ、`restoreHotbar()` を `clearPlayerPresentation()` へ改名し、呼び出し元とテストを更新した。
- 変更ファイル: `SkillTreeService.java`, `SkillTreeEventHandler.java`, `SkillTreeEventHandlerTest.java`

## 未確認質問

### Q-CODE-001
- 関連指摘: `AR-CODE-001`
- 確認事項: スキルツリー進行状態の初回ロード中に、通常スキル発動・ステータス計算を一時的に保留するか、前回キャッシュがなければスキルツリー由来分だけ未適用として進めるか。
- 状態: 未解決

## 修正スキル入力サマリ
- 自動修正候補: `AR-CODE-002`, `AR-CODE-003` -> 修正済み
- 要確認: `AR-CODE-001`, `Q-CODE-001` -> 未対応
- 推奨残作業: `AR-CODE-001` の仕様判断後に別途修正
- 対象範囲: `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skilltree`

## 確認した範囲
- 対象プロジェクト: `10_plugin/AstralRecord`
- 読んだ設計書/ルール: `AGENTS.md`, `README.md`, `PLUGIN_GUIDE.md`, `.codex/skills/astralrecord-code/references/plugin-code.md`, `.codex/skills/astralrecord-code-fix/SKILL.md`, `00_docs/10_プラグイン設計書/feature/13-skill/4-統合フロー/13_4.01-スキルバインドGUI.md`, `00_docs/10_プラグイン設計書/feature/13-skill/5-例外・ログ・運用/13_5.00-例外・ログ・運用.md`
- 読んだソース: `feature/skilltree` 配下、`LogId.java`, `logger.properties`, 関連テスト
- 実行した検証: 参照検索と diff 確認。Maven 実行は未実施（`mvn` が PATH に存在せず、Maven Wrapper もリポジトリに存在しない）。

## 対象外
- `AR-CODE-001` の仕様判断と実装修正。
- API / DB 実装全体の変更。
