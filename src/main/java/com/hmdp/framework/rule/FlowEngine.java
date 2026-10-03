package com.hmdp.framework.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 规则树引擎：从起始节点一路跑到终点，返回终点产出的结果。
 *
 * <p>防护分两层，分别针对两类不同的问题：
 * <ul>
 *   <li><b>判环</b>：单次运行内按对象身份记录访问过的节点，同一节点被重复访问即判定成环，
 *       立即抛出异常并打印环路路径。错误装配或数据驱动的分支想转圈，第一次重访就会被拦下；</li>
 *   <li><b>步数上限</b>：兜底"合法但病态的长路径"（路径爆炸）。默认 {@link #DEFAULT_MAX_STEPS} 步，
 *       每棵树可通过 {@link #run(FlowNode, Object, Object, int)} 按自身复杂度显式指定。</li>
 * </ul>
 */
public final class FlowEngine {

    /** 默认步数上限：仅作路径爆炸的兜底，正常树的最长路径远小于该值。 */
    public static final int DEFAULT_MAX_STEPS = 16;

    private FlowEngine() {
    }

    /** 使用默认步数上限运行规则树。 */
    public static <T, C, R> R run(FlowNode<T, C, R> start, T request, C context) throws Exception {
        return run(start, request, context, DEFAULT_MAX_STEPS);
    }

    /**
     * 运行规则树。
     *
     * @param maxSteps 单次运行最多处理的节点数，必须为正数
     * @throws IllegalStateException 检测到环，或已处理的节点数达到 maxSteps 仍未到达终点
     */
    public static <T, C, R> R run(FlowNode<T, C, R> start, T request, C context, int maxSteps) throws Exception {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps 必须为正数：" + maxSteps);
        }
        Set<FlowNode<T, C, R>> visited = Collections.newSetFromMap(
                new IdentityHashMap<FlowNode<T, C, R>, Boolean>());
        List<FlowNode<T, C, R>> path = new ArrayList<>();
        FlowNode<T, C, R> node = start;
        R result = null;
        while (node != null) {
            if (!visited.add(node)) {
                throw new IllegalStateException("规则树检测到环：" + loopPath(path, node));
            }
            if (path.size() >= maxSteps) {
                throw new IllegalStateException("规则树超过步数上限（" + maxSteps + " 步）：" + pathNames(path));
            }
            path.add(node);
            result = node.process(request, context);
            node = node.next(request, context);
        }
        return result;
    }

    /** 从重复节点在路径中上一次出现的位置开始拼接环路，例如 a -> b -> a。 */
    private static <T, C, R> String loopPath(List<FlowNode<T, C, R>> path, FlowNode<T, C, R> repeat) {
        int from = 0;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i) == repeat) {
                from = i;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < path.size(); i++) {
            sb.append(path.get(i).name()).append(" -> ");
        }
        return sb.append(repeat.name()).toString();
    }

    /** 拼接已访问的节点路径，用于超限时的诊断信息。 */
    private static <T, C, R> String pathNames(List<FlowNode<T, C, R>> path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            sb.append(path.get(i).name());
        }
        return sb.toString();
    }
}
