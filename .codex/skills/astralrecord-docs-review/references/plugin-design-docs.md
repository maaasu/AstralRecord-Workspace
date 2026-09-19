# Plugin 設計書 reference

`E:\AstralRecord-Workspace\00_docs\10_Plugin設計書` 配下の path にはこの reference を使う。

## 必須コンテキスト

feature をレビューする前に次を読む。

- `00_docs/10_Plugin設計書/README.md`。
- 実装 ownership が関係する場合は `00_docs/10_Plugin設計書/FEATURE_CATALOG.md`。
- `feature/01-user/01_0-概要.md` のような対象 feature 概要。
- レビュー対象の挙動に関係する model、use-case、method-contract、flow、operation、planned-specification、未決事項の設計書。

用語、model、method、flow、dependency、未決事項を定義する場合は Wiki link と相対 Markdown link の両方をたどる。docs-only review 中は実装 path を inspection しない。`FEATURE_CATALOG.md` の path は ownership label としてだけ扱う。

## 確認する構造ルール

ルート設計 README を正本とする。現在想定する構造は次のとおり。

- feature directory: `2-digit-number-feature-name`。例: `01-user`。
- 必須の feature entry point: feature directory 直下の `<feature-number>_0-概要.md`。
- feature level の `<feature-number>_README.md` は置かない。
- 任意の category:
  - `1`: model 定義。
  - `2`: use case。
  - `3`: method 仕様と処理 contract。
  - `4`: integration flow。
  - `5`: exception、log、operation。
  - `6`: development と extension の guide。
  - `8`: 受け入れ済みだが未実装の仕様。
  - `9`: 未決の判断だけ。
- Markdown の file name: `<feature-number>_<category-number>-<meaningful-name>.md`。
- Markdown file が1つだけの category は feature directory に flat に置く。
- category directory は複数の Markdown file を含む場合だけ使う。
- H1 は `.md` を除いた file name と一致させる。
- file name は Plugin 設計書 tree 全体で一意にし、bracket や space を使わない。
- Wiki link と相対 Markdown link はどちらも有効。path のない Wiki link は一意に解決できなければならない。
- 空の category directory、空の未決事項設計書、`.00` や `.01` のような detail-number name は無効。

## Feature 概要のルール

feature 概要は navigation と責務の entry point である。固定見出しを要求するのではなく、feature に必要な情報が含まれるかをレビューする。

- 目的、責務、対象外。
- 主要な境界と不変条件。
- 依存する feature と cross-feature contract。
- 関連 data、setting、正本となる source。
- feature を理解するために必要な設計書への link。
- root rule を超える価値がある場合だけ feature 固有の変更影響。

実装 ownership path は `FEATURE_CATALOG.md` に置き、feature ごとに重複した table of contents には置かない。

## 確認する Method contract ルール

category `3` の設計書では次を確認する。

- 完全な method 一覧ではなく、外部から意味のある contract または cross-feature contract に焦点を当てる。
- 文書化された contract に関係する input、output、precondition、rejection condition、重要な判断、delegation、state change、persistence/thread boundary、failure behavior を必須とする。
- class name、物理的な method name、event name は任意。記載する場合はレビュー対象の設計と内部整合させる。ただし固定 label がなくても明確な table または grouped contract は欠陥ではない。
- 論理名は、可能な範囲で理解しやすい日本語の名詞句にする。
- file 間 reference には Wiki link または相対 Markdown link を使える。
- log/message には必要に応じて ID、level/type、trigger、argument、意味、運用上の対応を示す。properties file が正本として文書化されている場合、完全な message template は要求しない。

## 確認する Integration flow ルール

- 挙動が component をまたぐ、または local contract だけでは理解できない場合だけ integration-flow document を要求する。
- Mermaid は participant、分岐、非同期処理、compensation、state transition を図で示すことが実質的に明確化する場合だけ必須とする。
- 単純な flow は prose または table でよい。
- 図がある場合は、label と sequence が周辺の設計と一致するか確認する。

## 設計レビューの重点

設計レベルの問題を優先する。

- flow は method contract と feature 概要に一致しているか。
- model field は記載された use case と lifecycle に十分か。
- event、command、service、repository、cache/session、task、adapter/listener、operation docs の責務はきれいに分かれているか。
- cross-feature call は ownership と dependency direction が分かるほど明示されているか。
- failure path、null/not-found behavior、retry、logging、player 向け message、運用上の対応が必要な場所に記載されているか。
- login/logout、cache/session、save timing、cooldown、buff/status effect、item ownership、loot grant、その他 gameplay lifecycle の state transition は明確か。
- current behavior、受け入れた将来作業、未決の判断が明確に分かれているか。
- 文書が正本となる実装 path や message template を重複させていないか。

## 意図が不足している場合

概要、use case、flow diagram、category `8`、category `9`、関連 feature docs から意図を集める。それでも意図した挙動を決められない場合は、無理に欠陥とせず、必要な判断を具体的に示した `未確認/質問` として報告する。
