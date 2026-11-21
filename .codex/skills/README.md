# AstralRecord ワークスペースのスキルカタログ

このファイルはスキル選択用のカタログです。実行時は対象スキルの `SKILL.md` だけを読み、リンクされた参照は現在の作業に必要な場合だけ読みます。詳細な手順、チェックリスト、報告形式は各スキルに重複させません。

`SKILL.md` があるディレクトリだけを有効なスキルとし、スキル名・説明は各 `SKILL.md` のフロントマターを正本とします。

## 共通ルーティング

変更作業の入口と例外はルート `AGENTS.md` の実行ルールで選びます。下表の担当スキルは、統合入口で分類した後に読みます。

| 目的 | スキル |
|:--|:--|
| 作業ツリーの作成・完了処理・マージ | `$astralrecord-git-worktree-develop` |
| ローカル作業ツリーでの実装・検証と develop への反映 | `$astralrecord-code-version-commit-develop` |
| 実装、挙動・契約変更の担当 | `$astralrecord-code` |
| Minecraft 内スキルの担当 | `$astralrecord-skill-author` |
| 本番 ファイルベース 作成の担当 | `$astralrecord-master-data-author` |
| 指定 ファイルベース の高速直接作成（単一ライターの例外経路） | `$astralrecord-master-data-create-direct` |
| スキル定義・参照・スクリプト更新の担当 | `$skill-creator` |
| 開始・終了コミットや指定されたお知らせ内容からリリースノートMDの下書きを作成 | `$astralrecord-release-note-author` |
| 現在の作業差分だけをコミット | `$astralrecord-commit-current-diff` |
| コード・設定・資料・ワークスペーススキルのレビュー | `$astralrecord-code-review` |
| レビュー指摘の修正 | `$astralrecord-code-fix` |
| 複数の codex ブランチをマージ | `$astralrecord-merge-codex-branches-develop` |
| Codex・Claude のローカル作業ツリーとブランチの監査・整理 | `$astralrecord-prune-codex-worktrees` |

## 専用ルーティング

| 対象・目的 | スキル |
|:--|:--|
| 新規プレイヤー用アイテムと外観変更の Blockbench 制作・両版配布 | `$astralrecord-item-model-author` |
| モブ・ボスの外観の Blockbench 制作、立体意匠、両版の描画検証 | `$astralrecord-mob-model-author` |
| 下位モデルが判断せずに実装できるボス設計書の作成 | `$astralrecord-boss-design-author` |
| ボス設計書どおりの実装と、完了後の設計書削除 | `$astralrecord-boss-implement` |
| AstralArchitect の建築候補 | `$astralarchitect-builder` |
| Minecraft建築の共通完成度基準（チケット外の建築データにも適用） | `$minecraft-build-quality` |
| プラグインの恒久・診断・統合テスト | `$astralrecord-plugin-test` |
| プラグイン版番号 | `$astralrecord-plugin-version` |
| プレイヤー/ロガー propertiesの未使用削除 | `$astralrecord-unused-properties-prune` |

## 差分のない作業

質問、説明、診断、読み取り専用レビューでは、変更が発生しない限り作業ツリー、コミット、ビルド、品質ゲートを起動しません。必要な対象資料だけを読みます。

## 差分のある作業

- 統合入口を使う変更では、並列作業の有無にかかわらず、そこで作業の種類と品質ゲートを分類します。専用スキルへ直接進む例外はルート `AGENTS.md` の実行ルールに従います。
- 並列作業では、YAML 単位ではなく独立して検証できるパッケージ単位で作業ツリーを分けます。
- ファイルベース の develop への直接コミットは、明示的な単一ライター作業に限ります。
- プラグインの版番号更新は、リベース後の完了処理で必要な場合だけ行います。
- 対象外プロジェクトのガイド、テスト方針、参照、プラグイン版番号更新手順は読みません。

## 新しいスキル

新しいスキルを追加した場合は、ここに「スキル名・目的・使う場面」だけを1行追加します。詳細はスキル内に置き、ルート `AGENTS.md` へ個別追記しません。

## Claude Code との共用

このディレクトリが Codex / Claude Code 共通の唯一の正本です。Claude Code は `.claude/skills`（`.codex/skills` への junction。`60_tool/agent-config/link-skills.ps1` が作成）経由で同じファイルを読みます。スキルの追加・編集はここだけで行い、`.claude/skills` 側に実体を置かないでください。
