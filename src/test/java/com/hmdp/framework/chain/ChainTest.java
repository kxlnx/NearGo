package com.hmdp.framework.chain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 责任链执行器单测：顺序执行、提前终止与异常中断。
 */
class ChainTest {

    static class RecordingNode implements ChainNode<String, List<String>> {

        private final String name;
        private final boolean proceed;

        RecordingNode(String name, boolean proceed) {
            this.name = name;
            this.proceed = proceed;
        }

        @Override
        public boolean process(String request, List<String> context) {
            context.add(name);
            return proceed;
        }
    }

    @Test
    void runsNodesInOrder() throws Exception {
        List<String> context = new ArrayList<>();
        Chain<String, List<String>> chain = new Chain<>(Arrays.asList(
                new RecordingNode("a", true),
                new RecordingNode("b", true),
                new RecordingNode("c", true)));

        chain.execute("request", context);

        assertEquals(Arrays.asList("a", "b", "c"), context);
        assertEquals(3, chain.size());
    }

    @Test
    void stopsWhenNodeReturnsFalse() throws Exception {
        List<String> context = new ArrayList<>();
        Chain<String, List<String>> chain = new Chain<>(Arrays.asList(
                new RecordingNode("a", true),
                new RecordingNode("b", false),
                new RecordingNode("c", true)));

        chain.execute("request", context);

        assertEquals(Arrays.asList("a", "b"), context);
    }

    @Test
    void propagatesExceptionAndStopsChain() {
        List<String> context = new ArrayList<>();
        ChainNode<String, List<String>> boom = (request, ctx) -> {
            ctx.add("boom");
            throw new IllegalStateException("节点校验失败");
        };
        Chain<String, List<String>> chain = new Chain<>(Arrays.asList(
                new RecordingNode("a", true),
                boom,
                new RecordingNode("c", true)));

        assertThrows(IllegalStateException.class, () -> chain.execute("request", context));
        assertEquals(Arrays.asList("a", "boom"), context);
    }
}
