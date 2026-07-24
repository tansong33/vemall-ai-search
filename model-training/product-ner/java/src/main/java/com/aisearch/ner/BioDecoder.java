package com.aisearch.ner;

import java.util.ArrayList;
import java.util.List;

/**
 * BIO 标签序列 -> 字符级实体，必须和 Python 的 nerkit/labels.py + alignment.py 行为一致。
 *
 * <p>三条容易被忽略、但必须照抄的规则：
 * <ol>
 *   <li>孤立的 {@code I-X}（前面没有 {@code B-X}）视为**开启一个新实体**，而不是丢弃；
 *       模型在没有 CRF 约束时经常这么输出，丢弃会白白损失召回。</li>
 *   <li>实体两端的空白必须裁掉：子词 token 的 span 可能带上空格。</li>
 *   <li>置信度低于 {@code tau_accept}（从 ner_manifest.json 读取）的实体直接丢弃。</li>
 * </ol>
 */
public final class BioDecoder {

    private final String[] id2tag;
    private final double tauAccept;

    public BioDecoder(String[] id2tag, double tauAccept) {
        this.id2tag = id2tag;
        this.tauAccept = tauAccept;
    }

    /**
     * @param original    原始 query（offset 就是针对它的）
     * @param tagIds      每个 token 的标签 id（ONNX 输出的 tag_ids）
     * @param confidence  每个 token 的置信度（ONNX 输出的 confidence）
     * @param tokenStart  每个 token 在原串中的起始字符下标
     * @param tokenEnd    每个 token 在原串中的结束字符下标（不含）
     * @param isSpecial   该 token 是否是 [CLS]/[SEP] 等特殊 token
     */
    public List<NerEntity> decode(String original, long[] tagIds, float[] confidence,
                                  int[] tokenStart, int[] tokenEnd, boolean[] isSpecial) {
        List<NerEntity> out = new ArrayList<>();
        String curLabel = null;
        int curStart = -1;
        double confSum = 0;
        int confCount = 0;

        for (int i = 0; i < tagIds.length; i++) {
            if (isSpecial[i] || tokenEnd[i] <= tokenStart[i]) {
                continue;   // 特殊 token 和零宽 token 不参与解码
            }
            String tag = id2tag[(int) tagIds[i]];
            String prefix = tag.equals("O") ? "O" : tag.substring(0, tag.indexOf('-'));
            String label = tag.equals("O") ? null : tag.substring(tag.indexOf('-') + 1);

            if ("O".equals(prefix)) {
                flush(out, original, curLabel, curStart, i, tokenStart, tokenEnd,
                        confSum, confCount, i);
                curLabel = null;
                confSum = 0;
                confCount = 0;
                continue;
            }
            if ("B".equals(prefix) || "S".equals(prefix)) {
                flush(out, original, curLabel, curStart, i, tokenStart, tokenEnd,
                        confSum, confCount, i);
                curLabel = label;
                curStart = tokenStart[i];
                confSum = confidence[i];
                confCount = 1;
                if ("S".equals(prefix)) {
                    addEntity(out, original, curLabel, curStart, tokenEnd[i], confSum / confCount);
                    curLabel = null;
                    confSum = 0;
                    confCount = 0;
                }
            } else { // I- 或 E-
                if (label != null && label.equals(curLabel)) {
                    confSum += confidence[i];
                    confCount++;
                    if ("E".equals(prefix)) {
                        addEntity(out, original, curLabel, curStart, tokenEnd[i], confSum / confCount);
                        curLabel = null;
                        confSum = 0;
                        confCount = 0;
                    }
                } else {
                    // 孤立的 I-/E-：收尾旧实体，并把它当成新实体的开头
                    flush(out, original, curLabel, curStart, i, tokenStart, tokenEnd,
                            confSum, confCount, i);
                    curLabel = label;
                    curStart = tokenStart[i];
                    confSum = confidence[i];
                    confCount = 1;
                    if ("E".equals(prefix)) {
                        addEntity(out, original, curLabel, curStart, tokenEnd[i], confSum / confCount);
                        curLabel = null;
                        confSum = 0;
                        confCount = 0;
                    }
                }
            }
        }
        // 序列结束时还开着的实体
        if (curLabel != null && confCount > 0) {
            int lastEnd = lastRealEnd(tokenEnd, isSpecial, tokenStart);
            addEntity(out, original, curLabel, curStart, lastEnd, confSum / confCount);
        }
        return out;
    }

    private void flush(List<NerEntity> out, String original, String curLabel, int curStart,
                       int i, int[] tokenStart, int[] tokenEnd, double confSum, int confCount,
                       int stopIdx) {
        if (curLabel == null || confCount == 0) return;
        int end = 0;
        for (int j = 0; j < stopIdx; j++) {
            if (tokenEnd[j] > end) end = tokenEnd[j];
        }
        addEntity(out, original, curLabel, curStart, end, confSum / confCount);
    }

    private int lastRealEnd(int[] tokenEnd, boolean[] isSpecial, int[] tokenStart) {
        int end = 0;
        for (int j = 0; j < tokenEnd.length; j++) {
            if (!isSpecial[j] && tokenEnd[j] > tokenStart[j] && tokenEnd[j] > end) {
                end = tokenEnd[j];
            }
        }
        return end;
    }

    private void addEntity(List<NerEntity> out, String original, String label,
                           int start, int end, double confidence) {
        // 裁掉两端空白（Python: text_norm.strip_span）
        while (start < end && Character.isWhitespace(original.charAt(start))) start++;
        while (end > start && Character.isWhitespace(original.charAt(end - 1))) end--;
        if (end <= start || confidence < tauAccept) return;
        out.add(new NerEntity(original.substring(start, end), label, start, end, confidence));
    }
}
