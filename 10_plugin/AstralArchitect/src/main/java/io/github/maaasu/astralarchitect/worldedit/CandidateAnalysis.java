package io.github.maaasu.astralarchitect.worldedit;

import java.util.List;

/**
 * 検証済み候補のブロック差分です。
 *
 * @param changes 変更一覧
 */
public record CandidateAnalysis(List<SchematicChange> changes) {

    /**
     * 変更ブロック数を返します。
     *
     * @return 変更ブロック数
     */
    public long changedBlockCount() {
        return changes.size();
    }
}
