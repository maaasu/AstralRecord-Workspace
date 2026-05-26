package io.github.maaasu.astralRecord.feature.skill.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code params} の取得・検証に失敗したことを示す例外。
 * <p>
 * 起動時の一括検証では {@link io.github.maaasu.astralRecord.feature.skill.service.SkillService}
 * が捕捉し、対象スキルを定義無効として隔離する。発動時に発生した場合も同様に発動失敗扱いとする。
 */
public class SkillParameterException extends RuntimeException {

    private final String key;

    /**
     * 不足キーを伴う例外を生成します。
     *
     * @param key     不足・型不一致のキー
     * @param message 詳細メッセージ
     */
    public SkillParameterException(@Nullable String key, @NotNull String message) {
        super(message);
        this.key = key;
    }

    /**
     * 不足・型不一致が発生したキーを返します。
     *
     * @return キー名。不明なら {@code null}
     */
    @Nullable
    public String key() {
        return key;
    }
}
