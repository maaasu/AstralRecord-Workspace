package io.github.maaasu.astralRecord.infrastructure.util;

import java.util.Map;

/**
 * YAMLファイル内の参照（ref）を解決するためのユーティリティ
 */
public class ReferenceUtil {

    /**
     * オブジェクトからIDを抽出します。
     * 文字列の場合はそのまま、Mapの場合は "ref" キーの値を返します。
     *
     * @param obj 参照オブジェクト
     * @return 抽出されたID。解決できない場合はnull
     */
    public static String resolveId(Object obj) {
        if (obj instanceof String) {
            return (String) obj;
        }
        if (obj instanceof Map<?, ?> map) {
            Object ref = map.get("ref");
            return ref != null ? ref.toString() : null;
        }
        return null;
    }
}

