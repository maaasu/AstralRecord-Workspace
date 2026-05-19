# StatusType 共通定義

プラグイン側（`io.github.maaasu.astralRecord.feature.status.model.StatusType`）で定義されるステータス名の一覧です。

本プロジェクト内で `status` フィールドを持つすべてのリソース（mob / class / buff / equipment など）は、このファイルを参照してください。

> **注意**: StatusType の正式定義はプラグイン側にあります。プラグイン側の定義に変更が入った場合は、本ファイルを更新してください。本ファイルを参照している各スキーマ定義は個別に更新不要です。

---
 
## StatusType 一覧

| カテゴリ     | StatusType                | 表示名      | 説明                                |
|:---------|:--------------------------|:---------|:----------------------------------|
| RESOURCE | `MAX_HEALTH`              | 最大HP     |                                   |
| RESOURCE | `MAX_MANA`                | 最大MP     |                                   |
| RESOURCE | `MAX_ENERGY`              | 最大EN     | スキル発動・ダッシュ・回避行動等に消費               |
| PRIMARY  | `STRENGTH`                | 筋力       | 近接攻撃のダメージスケーリングに影響                |
| PRIMARY  | `DEXTERITY`               | 器用さ      | 間接攻撃（弓・投擲等）のダメージスケーリングに影響         |
| PRIMARY  | `INTELLIGENCE`            | 知力       | 魔法攻撃スケーリング・最大MP・MP回復に影響           |
| PRIMARY  | `VITALITY`                | 体力       | 最大HP・物理/魔法防御・HP回復に影響              |
| PRIMARY  | `AGILITY`                 | 敏捷性      | 攻撃速度・移動速度・回避率に影響                  |
| PRIMARY  | `LUCK`                    | 幸運       | 会心率・ドロップ率に影響                      |
| OFFENSE  | `ATTACK`                  | 攻撃力      | 武器のベース攻撃力 + バフ等の加算値               |
| OFFENSE  | `MELEE_ATTACK`            | 近接攻撃力    | ATTACK × STRENGTH スケーリング          |
| OFFENSE  | `RANGED_ATTACK`           | 間接攻撃力    | ATTACK × DEXTERITY スケーリング         |
| OFFENSE  | `MAGIC_ATTACK`            | 魔法攻撃力    | ATTACK × INTELLIGENCE スケーリング      |
| OFFENSE  | `CRITICAL_RATE`           | 会心率      | 攻撃時に会心が発生する確率（%）                  |
| OFFENSE  | `CRITICAL_DAMAGE`         | 会心ダメージ   | 会心発生時のダメージ倍率（%）                   |
| OFFENSE  | `SUPER_CRITICAL_RATE`     | 超会心率     | 会心時にさらに発動する第二の会心確率（%）             |
| OFFENSE  | `SUPER_CRITICAL_DAMAGE`   | 超会心ダメージ  | 超会心発動時に追加乗算される倍率（%）               |
| OFFENSE  | `FINAL_DAMAGE_RATE`       | 最終ダメージ確率 | 全ダメージ計算後に追加ダメージが発動する確率（%）         |
| OFFENSE  | `FINAL_DAMAGE_MULTIPLIER` | 最終ダメージ倍率 | 追加ダメージ発動時の倍率（%）                   |
| OFFENSE  | `ACCURACY`                | 命中率      | 攻撃がヒットする確率（%）。EVASIONとの対抗判定       |
| OFFENSE  | `ATTACK_SPEED`            | 攻撃速度     | 攻撃のクールダウン短縮割合（%）                  |
| DEFENSE  | `DEFENSE`                 | 物理防御力    | 近接・間接攻撃による物理ダメージを軽減               |
| DEFENSE  | `MAGIC_DEFENSE`           | 魔法防御力    | 魔法攻撃によるダメージを軽減                    |
| DEFENSE  | `EVASION`                 | 回避率      | 攻撃を完全に回避する確率（%）                   |
| UTILITY  | `HP_REGEN`                | HP回復力    | HP自然回復量（5秒あたり）                    |
| UTILITY  | `MP_REGEN`                | MP回復力    | MP自然回復量（5秒あたり）                    |
| UTILITY  | `ENERGY_REGEN`            | EN回復力    | エネルギー自然回復量（5秒あたり）                 |
| UTILITY  | `MOVEMENT_SPEED`          | 移動速度     | 100% が標準速度（%）                     |
| UTILITY  | `COOLDOWN_REDUCTION`      | CD短縮     | クールダウン短縮率（%）                      |
