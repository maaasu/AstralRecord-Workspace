@AGENTS.md

## Claude Code 固有 `/<name>` 形式プロンプトの扱い

ユーザーがメッセージ冒頭で `/<name>` 形式のプロンプト（例: `/astralrecord-code <引数>`、`/astralrecord-docs-review <パス>`）を入力した場合、これは **codex skills への参照** として扱うこと。Claude Code の組み込みスラッシュコマンドや本ファイル冒頭のスキルリストに同名が存在しない限り、以下の手順で処理する。

### 解決手順

1. `<name>` を skill 名として `E:\AstralRecord-Workspace\.codex\skills\<name>\SKILL.md` を Read する。
2. ファイルが存在しない場合は、`E:\AstralRecord-Workspace\.codex\skills\` 配下を一覧し、`<name>` に近い skill を提示してユーザーに確認する。
3. `SKILL.md` の frontmatter（`name` / `description`）と本文を **そのまま自分への指示** として解釈し、記載された手順に従って作業する。
4. `SKILL.md` 内で `references/*.md` などの追加ファイルを読むよう指示されている場合は、その指示に従って読み込む。
5. `/<name>` 以降の文字列は、skill への引数（対象パス・カスタム指示など）として扱う。

### 等価関係

- codex における `$<skill-name>` 呼び出しと、Claude Code における `/<skill-name>` 呼び出しは **同一 skill（`.codex/skills/<skill-name>/SKILL.md`）を指す**。
- skill 本体の追加・編集は `.codex/skills/` 側で行う。Claude Code 側に複製ファイルを置く必要はない。

### 注意

- `/<name>` が Claude Code の組み込みコマンドや本セッションの利用可能スキル一覧に存在する場合は、そちらが優先される。混同しそうな場合はユーザーに確認する。
- skill 実行前に AGENTS.md の「対象判定ルール」「実行前チェック」を必ず満たすこと。
