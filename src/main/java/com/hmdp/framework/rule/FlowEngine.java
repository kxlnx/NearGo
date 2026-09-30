package com.hmdp.framework.rule;

/**
 * 规则树引擎：从起始节点一路跑到终点，返回终点产出的结果。
 * 带步数上限，防止装配错误导致成环死循环。
 */
public final class FlowEngine {

    private static final int MAX_STEPS = 100;

    private FlowEngine() {
    }

    public static <T, C, R> R run(FlowNode<T, C, R> start, T request, C context) throws Exception {
        FlowNode<T, C, R> node = start;
        R result = null;
        int step = 0;
        while (node != null) {
            if (++step > MAX_STEPS) {
                throw new IllegalStateException("规则树疑似成环（超过 " + MAX_STEPS + " 步）");
            }
            result = node.process(request, context);
            node = node.next(request, context);
        }
        return result;
    }
}
