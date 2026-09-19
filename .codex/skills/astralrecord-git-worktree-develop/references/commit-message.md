# Commit message の reference

正本には `E:\AstralRecord-Workspace\COMMIT_RULES.md` を使う。この file は選択の簡易指針だけを示す。

## Type の選択

- `feat`: feature またはユーザーから見える capability
- `fix`: bug fix または誤った挙動
- `docs`: documentation だけ
- `refactor`: 挙動を保持した code cleanup
- `test`: test だけ
- `build`: build、dependency、CI、packaging
- `chore`: workspace tooling、skill、maintenance、metadata

## Summary のルール

- 簡潔な subject line を1つ使う。
- 日本語で依頼された task では日本語を優先する。
- process ではなく実際の diff を記載する。
- 除外した local file に言及しない。

Examples:

- `chore: worktree 運用 skill を追加`
- `docs: skill README の git 運用説明を更新`
- `fix: branch slug 生成時の衝突判定を修正`
