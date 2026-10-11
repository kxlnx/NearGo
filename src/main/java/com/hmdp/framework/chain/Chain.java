package com.hmdp.framework.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 责任链执行器（链表版）：持有链头，负责一次调用的入口。
 *
 * <p><b>为什么判环放在装配期</b>：链是<b>静态结构</b>——下一跳由装配期的 {@code setNext} 决定，
 * 不依赖运行时数据，所以成环只可能来自"装配写错"。{@link #assemble} 在启动时遍历一遍
 * {@code next} 指针，一旦回到走过的节点立刻抛异常并打印环路路径，<b>应用起不来</b>，
 * 而不是等到线上某次请求把栈打爆。
 *
 * <p>（对比规则树：它的下一跳由 {@code next()} 按数据决定，是<b>动态结构</b>，
 * 环可能来自数据驱动的分支，所以必须每次运行都检测 —— 见 {@code FlowEngine}。）
 *
 * <p>装配完成后链结构不可再改（{@link ChainNode#setNext} 只允许调用一次），
 * 因此运行期 {@link #execute} 只是一次方法调用，没有任何额外开销。
 *
 * @param <T> 请求参数类型
 * @param <C> 上下文类型
 * @param <R> 结果类型
 */
public final class Chain<T, C, R> {

    private final ChainNode<T, C, R> head;
    private final int size;

    private Chain(ChainNode<T, C, R> head, int size) {
        this.head = head;
        this.size = size;
    }

    /**
     * 装配入口：把串好的链头交进来，做一次静态判环 + 计数。
     *
     * @throws IllegalStateException    链上出现环（重复访问同一节点），异常信息带环路路径
     * @throws IllegalArgumentException 链头为空
     */
    public static <T, C, R> Chain<T, C, R> assemble(ChainNode<T, C, R> head) {
        if (head == null) {
            throw new IllegalArgumentException("责任链头节点不能为空");
        }
        Set<ChainNode<T, C, R>> visited = Collections.newSetFromMap(
                new IdentityHashMap<ChainNode<T, C, R>, Boolean>());     // 按对象身份记录，重访即成环
        List<ChainNode<T, C, R>> path = new ArrayList<>();                // 记录路径供报错
        ChainNode<T, C, R> node = head;
        while (node != null) {
            if (!visited.add(node)) {
                throw new IllegalStateException("责任链装配成环：" + loopPath(path, node));
            }
            path.add(node);
            node = node.nextNode();
        }
        return new Chain<>(head, path.size());
    }

    /** 执行整条链：从链头开始，结果由终点（或提前结束的节点）产出。 */
    public R execute(T request, C context) throws Exception {
        return head.apply(request, context);
    }

    /** 链上节点数量，便于测试与日志。 */
    public int size() {
        return size;
    }

    /** 从重复节点在路径中上一次出现的位置开始拼接环路，例如 a -> b -> a。 */
    private static <T, C, R> String loopPath(List<ChainNode<T, C, R>> path, ChainNode<T, C, R> repeat) {
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
}
