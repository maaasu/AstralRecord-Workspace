# Task Routing

この参照は、対象workerや品質ゲートを決めるときだけ読む。分類後は不要なプロジェクト資料を読まない。

## 最初の分岐

| 条件 | 動作 |
|:--|:--|
| 回答、説明、診断、読み取り専用レビューで差分なし | 統合skillを起動しない。worktree/build/commit不要 |
| typo、コメント、表示文言、非動作metadataの一ファイル | Light gate。対象資料とworker、独立reviewerだけ |
| feature、behavior、executable script、schema/data contract、workspace skill logic、複数ファイル | Standard gate |
| `40_filebase` の並列作成 | package単位のworktree。`parallel-filebase.md`も読む |
| 既存レビュー記録の修正 | Review-fix entry。記録のあるworktreeを再利用できるか先に確認 |

## 参照の読み込み予算

- 常に読む: ルート `AGENTS.md`、worktree管理参照、対象workerの `SKILL.md`。
- 対象だけ読む: Plugin/API/Web/Architect/Filebase/Toolsの `Read Next`。複数対象でない限り他projectのguideは読まない。
- Standard gateだけ読む: `quality-gate.md`、対象projectのbuild/test policy、review固有reference。
- Filebase並列だけ読む: `parallel-filebase.md`、該当カテゴリのschema/checklist。
- Pluginのtest traceabilityは、test source、Plugin POM、許可design input、test-policy pathのいずれかが差分に含まれるときだけ読む・実行する。
- Plugin versionは、rebase後にPlugin source/resource/build fileが残るときだけ読む・実行する。

## 対象別の省略

| 対象 | 読まないもの |
|:--|:--|
| API / Web | Minecraft Plugin guide、Plugin version、Plugin test traceability |
| docsのみ | 実装worker、Java/C# build、Plugin version |
| Filebaseのみ | Java/API/Web build、Plugin version。ただしschema・ID・変更参照は維持 |
| `.codex/skills`のみ | Plugin guide、Plugin version、Minecraft test。`$skill-creator`とskill reviewは維持 |
| Architect候補編集 | AstralRecord Plugin/API guide。`$astralarchitect-builder`とArchitect規則だけ |
| typo/comment/metadata | full build、Round 2、specialist。対象projectの既存必須checkは除外しない |
