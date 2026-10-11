package com.hmdp.framework.chain;

/**
 * 责任链节点（链表版）：每个节点只做一件事，并决定是否把请求交给下一跳。
 *
 * <p>节点不持有任何请求级状态（Spring 单例），一次调用的数据全部放在上下文 {@code C} 里。
 *
 * <p>三条约定：
 * <ul>
 *   <li><b>校验通过 → 调 {@link #next}</b> 把接力棒交下去；</li>
 *   <li><b>命中幂等 / 无需继续 → 直接 return 结果</b>（链到此结束，属于正常结束，不是异常）；</li>
 *   <li><b>出错 → 抛异常</b> 中断链路并向上抛出，由编排层决定事务回滚与重试口径。</li>
 * </ul>
 *
 * <p>与规则树的分工：链是固定顺序的检验型流水线，树是可分叉的决策结构。
 *
 * @param <T> 请求参数类型（一次调用的输入，只读）
 * @param <C> 上下文类型（节点之间传递的数据）
 * @param <R> 结果类型（终点节点产出，或提前结束的节点产出）
 */
public abstract class ChainNode<T, C, R> {

    /** 下一跳：装配期由 {@link #setNext} 注入一次，之后只读 */
    private ChainNode<T, C, R> next;

    /**
     * 串链：只在启动装配时调用一次，返回下一跳以便链式书写。
     *
     * <p>重复设置直接失败——装配是启动期行为，链结构在运行期不允许再改。
     */
    public ChainNode<T, C, R> setNext(ChainNode<T, C, R> next) {
        if (this.next != null) {
            throw new IllegalStateException("节点 " + name() + " 的 next 已装配过，不允许重复串链");
        }
        this.next = next;
        return next;
    }

    /**
     * 接力棒：中间节点校验通过后调用；终点节点不调用。
     *
     * @return 后面所有节点一直到终点产出的结果
     */
    protected R next(T request, C context) throws Exception {
        if (next == null) {
            throw new IllegalStateException("节点 " + name() + " 没有下一跳却调用了 next()，请检查装配顺序");
        }
        return next.apply(request, context);
    }

    /** 本节点业务：查 → 拦 → 写上下文 → 接力（或直接 return 结果表示链到此结束）。 */
    public abstract R apply(T request, C context) throws Exception;

    /** 节点名称：装配期判环的报错路径展示用，默认取类名，可重写。 */
    public String name() {
        return getClass().getSimpleName();
    }

    /** 仅供 {@link Chain#assemble} 在装配期遍历判环使用。 */
    final ChainNode<T, C, R> nextNode() {
        return next;
    }
}
