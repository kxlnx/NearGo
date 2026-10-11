package com.hmdp.framework.chain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 责任链（链表版）单测：顺序接力、提前结束、异常中断、装配期判环。
 */
class ChainTest {

    /** 假节点：把名字记进上下文；relay = true 表示接力，false 表示"到此结束" */
    static class RecordingNode extends ChainNode<String, List<String>, String> {

        private final String name;
        private final boolean relay;

        RecordingNode(String name, boolean relay) {
            this.name = name;
            this.relay = relay;
        }

        @Override
        public String apply(String request, List<String> context) throws Exception {
            context.add(name);
            return relay ? next(request, context) : name + "->stop";
        }

        @Override
        public String name() {
            return name;
        }
    }

    @Test
    void runsNodesInOrderAndReturnsTerminalResult() throws Exception {
        List<String> context = new ArrayList<>();
        RecordingNode a = new RecordingNode("a", true);
        RecordingNode b = new RecordingNode("b", true);
        RecordingNode end = new RecordingNode("end", false);
        a.setNext(b).setNext(end);

        Chain<String, List<String>, String> chain = Chain.assemble(a);
        String result = chain.execute("request", context);

        assertEquals(Arrays.asList("a", "b", "end"), context);
        assertEquals("end->stop", result);
        assertEquals(3, chain.size());
    }

    @Test
    void stopsWhenNodeDoesNotRelay() throws Exception {
        List<String> context = new ArrayList<>();
        RecordingNode a = new RecordingNode("a", true);
        RecordingNode skip = new RecordingNode("skip", false);   // 命中幂等：不接力
        RecordingNode c = new RecordingNode("c", true);
        a.setNext(skip).setNext(c);

        String result = Chain.assemble(a).execute("request", context);

        assertEquals(Arrays.asList("a", "skip"), context);       // c 不会被执行
        assertEquals("skip->stop", result);
    }

    @Test
    void propagatesExceptionAndStopsChain() {
        List<String> context = new ArrayList<>();
        ChainNode<String, List<String>, String> boom = new ChainNode<String, List<String>, String>() {
            @Override
            public String apply(String request, List<String> ctx) {
                ctx.add("boom");
                throw new IllegalStateException("节点校验失败");
            }
        };
        RecordingNode a = new RecordingNode("a", true);
        RecordingNode c = new RecordingNode("c", true);
        a.setNext(boom).setNext(c);

        Chain<String, List<String>, String> chain = Chain.assemble(a);

        assertThrows(IllegalStateException.class, () -> chain.execute("request", context));
        assertEquals(Arrays.asList("a", "boom"), context);
    }

    @Test
    void rejectsSelfLoopAtAssembly() {
        RecordingNode a = new RecordingNode("a", true);
        a.setNext(a);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Chain.assemble(a));

        assertTrue(e.getMessage().contains("责任链装配成环"), e.getMessage());
        assertTrue(e.getMessage().contains("a -> a"), e.getMessage());
    }

    @Test
    void reportsLoopPathAtFirstRevisit() {
        RecordingNode a = new RecordingNode("a", true);
        RecordingNode b = new RecordingNode("b", true);
        a.setNext(b);
        b.setNext(a);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Chain.assemble(a));

        assertTrue(e.getMessage().contains("a -> b -> a"), e.getMessage());
    }

    @Test
    void rejectsSecondSetNext() {
        RecordingNode a = new RecordingNode("a", true);
        RecordingNode b = new RecordingNode("b", true);
        RecordingNode c = new RecordingNode("c", true);
        a.setNext(b);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.setNext(c));

        assertTrue(e.getMessage().contains("不允许重复串链"), e.getMessage());
    }

    @Test
    void rejectsEmptyChain() {
        assertThrows(IllegalArgumentException.class, () -> Chain.assemble(null));
    }
}
