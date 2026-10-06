package com.hmdp.framework.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 责任链执行器：按装配顺序依次执行节点。
 *
 * <p>节点返回 false 表示链路提前完成；节点抛异常则中断链路并向上抛出，
 * 由编排层决定事务回滚与失败统计口径。
 */
public final class Chain<T, C> {

    private final List<ChainNode<T, C>> nodes;

    public Chain(List<ChainNode<T, C>> nodes) {
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    public void execute(T request, C context) throws Exception {
        for (ChainNode<T, C> node : nodes) {
            if (!node.process(request, context)) {
                return;
            }
        }
    }

    /** 链上节点数量，便于测试与日志。 */
    public int size() {
        return nodes.size();
    }
}
