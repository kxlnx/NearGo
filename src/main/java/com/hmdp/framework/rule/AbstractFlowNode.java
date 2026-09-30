package com.hmdp.framework.rule;

/**
 * 抽象节点：把"先补数据、再干活"的节奏定死，子类只写 handle。
 */
public abstract class AbstractFlowNode<T, C, R> implements FlowNode<T, C, R> {

    @Override
    public R process(T request, C context) throws Exception {
        loadData(request, context);
        return handle(request, context);
    }

    /** 钩子：默认空；需要并行预加载数据的节点才重写。 */
    protected void loadData(T request, C context) throws Exception {
    }

    /** 本节点业务：子类必填。 */
    protected abstract R handle(T request, C context) throws Exception;
}
