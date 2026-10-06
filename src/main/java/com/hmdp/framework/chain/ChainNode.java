package com.hmdp.framework.chain;

/**
 * 责任链节点：每个节点只做一件事，返回是否继续向下传递。
 *
 * <p>与规则树的区别：链是固定顺序的检验型流水线，树是可分叉的决策结构；
 * 校验步骤之间没有依赖分支时用链，需要按状态走不同处理路径时用树。
 *
 * @param <T> 请求参数类型（一次调用的输入，只读）
 * @param <C> 上下文类型（节点之间传递的数据）
 */
public interface ChainNode<T, C> {

    /**
     * 执行本节点校验/处理。
     *
     * @return true 继续下一个节点；false 表示本次处理已结束（如命中幂等，无需继续）
     * @throws Exception 校验失败等异常会中断链路并向上抛出
     */
    boolean process(T request, C context) throws Exception;
}
