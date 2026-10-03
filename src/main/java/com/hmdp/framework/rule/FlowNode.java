package com.hmdp.framework.rule;

/**
 * 规则树节点：每个节点做一件事，并决定下一跳。
 *
 * @param <T> 请求参数类型（一次调用的输入，只读）
 * @param <C> 上下文类型（一次调用过程中节点之间传递的数据）
 * @param <R> 结果类型（终点节点产出）
 */
public interface FlowNode<T, C, R> {

    /** 本节点业务：查数据、拦异常、写上下文。 */
    R process(T request, C context) throws Exception;

    /** 下一跳：返回 null 表示到达终点。分支判断写在这里。 */
    FlowNode<T, C, R> next(T request, C context) throws Exception;

    /** 节点名称：用于判环与超限异常中的路径展示，默认取类名，可重写。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
