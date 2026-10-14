# 中央登録manifest受入・統合検証

## 受入単位

B01 thread 01a13ae0-4ca1-7609-be4a-e9ef0c7ee0f6、B02 thread 01a13ae0-e7ec-74ee-af0a-eb0df5cc60eeは計画e2058ade基点で実装中。各返却は限定commit SHA、branch/worktree、所有paths、固定mob/skill/item IDs、中央変更依頼manifest、静的検証ログ、未検証を含む。親経由で受け取り、依頼が来る前に別スレッドを操作・取り込みしない。

manifestはbatches/B番号/CENTRAL_REQUESTS.mdなどに保存。必須: implementation ID/完全修飾Executor class/constructor依存と既存serviceの取り方/registerコード案、追加buff/loot参照、各skillのvalidate条件/params上限、必要な共有resourceの具体キー案、基盤変更理由、spawn生息想定と既存world/spawner根拠、未実施検証。変更なしの項目はなしと明記する。

## 受入検査

1. commitはe2058adeを祖先に持つ専用branch。git show --stat/--name-statusと対象diffを確認。中央ファイル、他batch素材・enemy、pom/schema/API/Web/Editorを含む場合は受け入れず担当へ分離を依頼。
2. BATCH_IDS.csv/NEW_MATERIALS.csvとIDを照合。全23種/33素材予約を重複なしで保持。技IDが増える場合は自種prefixだけ許可し統合台帳へ追記。専用loot/buffに別batch依存を作らない。
3. 型・constructor/JavaDoc・main thread制約・対象選別・予兆/範囲・CD/cast・死亡/target消失/despawn/停止時解除を静的レビュー。ParticleDisplayService等共通表現ルールを照合。
4. YAMLの4profile/幅6/最大50、固有skill/通常攻撃、item/loot/buff参照を確認。登録前のskill未解決はmanifestで明示されたものだけ暫定受入。重いbuild未実施を成功扱いしない。
5. 受入後、親の統合指示に従って個別commitを統合worktreeへ直列取り込み。中央登録/import/必要な共通resourceを統合担当だけが編集。既存共有基盤変更はまとめて独立reviewする。

## 全体検証の順番

A: 統合前後のYAML parseと適用schema、namespaceごとの全ID重複、全変更参照(item/mob/skill/buff/loot/world/spawner)、mob_skill IDのExecutor/registry照合、全profile実効値/既存drop保持を静的確認。専用Mob validator CLIは未確認なので存在しないコマンドを案内しない。

B: python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <integration-root>。全出力を保存し作業起因警告を解消。

C: 全バッチの必要登録が完了し、親とbuild枠を調整した時点でPluginディレクトリからmvn -q testを一度直列実行。標準出力/標準エラーを保存、Surefire件数と失敗/警告を確認。既存/外部/作業起因を分類。変更後の再実行は修正または新しい統合差分がある場合だけ。個別Mob恒久JUnitは作らず、共通不変条件を変えた場合だけ適切な共通test。

D: POM/test/許可設計入力/テスト方針変更が差分にある場合だけpython .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py。差分が条件に該当しない場合は省略理由を記録。mvn verifyはこのゲートに使わない。

E: 標準品質ゲート第1回独立review、必要な修正後に修正担当と異なる第2回review。高/中・未解決の自動修正指摘は停止。限定diff/check/文字化けcheck/限定commitを実行。filebase実装の取り込み後に素材対応CSVの確率/個数を確定値へ更新し、装備側へ親経由で共有。

F: 実server/clientで予兆・命中・回避・複数種共存・TTK/耐久・実drop・状態解除を確認する段階は現在未許可。world名/座標を捏造しない。spawner定義とruntime mob_spawners.ymlの設置を分け、配置根拠がある設定だけ中央担当が作成。server/DB/portを操作せず未実施として残す。

G: equipment-scalar-random側成果の完成・親の統合指示後、素材消費参照と装備レシピの成立を確認し用途なし一覧再計算。API/Web/Editorのbuildは装備担当の所有で、こちらのPlugin buildと重ならないよう親が直列枠を調整。push/develop merge/version/deployは別途指示までしない。

この資料は手順準備であり、B01/B02実装内容の受入・コード登録・buildはまだ実施していない。
