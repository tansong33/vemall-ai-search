package com.aisearch.ner;

/**
 * 一个实体。start/end 是**原始 query**（用户实际输入的那个字符串）的字符下标，
 * 含首不含尾，可以直接 {@code query.substring(start, end)} 用于高亮。
 */
public record NerEntity(String text, String label, int start, int end, double confidence) {

    public String label() {
        return label;
    }
}
