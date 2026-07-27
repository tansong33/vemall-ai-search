package cn.vetech.ai.search.server.service.ner;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * RaNER 线性链 CRF 的 Viterbi 解码器。
 *
 * <p>ModelScope 的 CRF 回溯实现使用了 {@code Tensor.data}，直接导出 ONNX 时会把编码器
 * 错误地常量折叠掉，得到一个丢失 StructBERT 权重的伪模型。因此 ONNX 只负责输出真实
 * emission，本类用训练时导出的同一组 start/end/transition 参数恢复最优标签路径。</p>
 */
final class CrfViterbiDecoder {

    private final double[] startTransitions;
    private final double[] endTransitions;
    private final double[][] transitions;

    private CrfViterbiDecoder(double[] startTransitions, double[] endTransitions,
                              double[][] transitions) {
        this.startTransitions = startTransitions;
        this.endTransitions = endTransitions;
        this.transitions = transitions;
    }

    static CrfViterbiDecoder fromJson(JsonNode root) {
        double[] start = vector(root.get("startTransitions"), "startTransitions");
        double[] end = vector(root.get("endTransitions"), "endTransitions");
        JsonNode matrixNode = root.get("transitions");
        if (matrixNode == null || !matrixNode.isArray() || matrixNode.size() != start.length) {
            throw new IllegalArgumentException("CRF transitions must be a square matrix");
        }
        if (end.length != start.length) {
            throw new IllegalArgumentException("CRF start/end dimensions differ");
        }
        double[][] matrix = new double[start.length][];
        for (int row = 0; row < start.length; row++) {
            matrix[row] = vector(matrixNode.get(row), "transitions[" + row + "]");
            if (matrix[row].length != start.length) {
                throw new IllegalArgumentException("CRF transitions must be a square matrix");
            }
        }
        return new CrfViterbiDecoder(start, end, matrix);
    }

    int size() {
        return startTransitions.length;
    }

    int[] decode(float[][] emissions) {
        if (emissions.length == 0) {
            return new int[0];
        }
        int tagCount = size();
        double[] score = new double[tagCount];
        int[][] history = new int[emissions.length][tagCount];
        validateEmission(emissions[0], tagCount, 0);
        for (int tag = 0; tag < tagCount; tag++) {
            score[tag] = startTransitions[tag] + emissions[0][tag];
        }

        for (int position = 1; position < emissions.length; position++) {
            validateEmission(emissions[position], tagCount, position);
            double[] next = new double[tagCount];
            for (int current = 0; current < tagCount; current++) {
                int bestPrevious = 0;
                double bestScore = score[0] + transitions[0][current];
                for (int previous = 1; previous < tagCount; previous++) {
                    double candidate = score[previous] + transitions[previous][current];
                    if (candidate > bestScore) {
                        bestScore = candidate;
                        bestPrevious = previous;
                    }
                }
                next[current] = bestScore + emissions[position][current];
                history[position][current] = bestPrevious;
            }
            score = next;
        }

        int bestTag = 0;
        double bestScore = score[0] + endTransitions[0];
        for (int tag = 1; tag < tagCount; tag++) {
            double candidate = score[tag] + endTransitions[tag];
            if (candidate > bestScore) {
                bestScore = candidate;
                bestTag = tag;
            }
        }

        int[] path = new int[emissions.length];
        path[path.length - 1] = bestTag;
        for (int position = path.length - 1; position > 0; position--) {
            path[position - 1] = history[position][path[position]];
        }
        return path;
    }

    private static double[] vector(JsonNode node, String name) {
        if (node == null || !node.isArray() || node.size() == 0) {
            throw new IllegalArgumentException("CRF " + name + " must be a non-empty array");
        }
        double[] values = new double[node.size()];
        for (int index = 0; index < node.size(); index++) {
            values[index] = node.get(index).asDouble();
        }
        return values;
    }

    private static void validateEmission(float[] emission, int expected, int position) {
        if (emission.length != expected) {
            throw new IllegalArgumentException("Emission label count differs at position "
                    + position + ": " + emission.length + " != " + expected);
        }
    }
}
