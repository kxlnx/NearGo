package com.hmdp.framework.rule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则树引擎单测：验证顺序执行、分支跳转、终点收口、判环与可配置步数上限。
 */
class FlowEngineTest {

    static class TestNode implements FlowNode<String, List<String>, String> {

        private final String name;
        private final String jumpWhen;
        private FlowNode<String, List<String>, String> nextNode;
        private final FlowNode<String, List<String>, String> branchNode;
        private final boolean terminal;

        TestNode(String name, String jumpWhen, FlowNode<String, List<String>, String> branchNode, boolean terminal) {
            this.name = name;
            this.jumpWhen = jumpWhen;
            this.branchNode = branchNode;
            this.terminal = terminal;
        }

        public TestNode then(TestNode next) {
            this.nextNode = next;
            return next;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String process(String request, List<String> context) {
            context.add(name);
            return terminal ? name + "->result" : null;
        }

        @Override
        public FlowNode<String, List<String>, String> next(String request, List<String> context) {
            if (branchNode != null && jumpWhen.equals(request)) {
                return branchNode;
            }
            return nextNode;
        }
    }

    @Test
    void runsInOrderAndReturnsTerminalResult() throws Exception {
        List<String> context = new ArrayList<>();
        TestNode end = new TestNode("end", null, null, true);
        TestNode b = new TestNode("b", null, null, false);
        b.then(end);
        TestNode a = new TestNode("a", null, null, false);
        a.then(b);

        String result = FlowEngine.run(a, "normal", context);

        assertEquals(Arrays.asList("a", "b", "end"), context);
        assertEquals("end->result", result);
    }

    @Test
    void takesBranchWhenConditionMatches() throws Exception {
        List<String> context = new ArrayList<>();
        TestNode end = new TestNode("end", null, null, true);
        TestNode branchEnd = new TestNode("branchEnd", null, null, true);
        TestNode root = new TestNode("root", "branch", branchEnd, false);
        root.then(end);

        String result = FlowEngine.run(root, "branch", context);

        assertEquals(Arrays.asList("root", "branchEnd"), context);
        assertEquals("branchEnd->result", result);
    }

    @Test
    void throwsWhenTreeIsCyclic() {
        TestNode node = new TestNode("loop", null, null, false);
        node.then(node);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FlowEngine.run(node, "x", new ArrayList<>()));

        assertTrue(e.getMessage().contains("规则树检测到环"), e.getMessage());
        assertTrue(e.getMessage().contains("loop -> loop"), e.getMessage());
    }

    @Test
    void reportsLoopPathAtFirstRevisit() {
        TestNode a = new TestNode("a", null, null, false);
        TestNode b = new TestNode("b", null, null, false);
        a.then(b);
        b.then(a);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FlowEngine.run(a, "x", new ArrayList<>()));

        assertTrue(e.getMessage().contains("a -> b -> a"), e.getMessage());
    }

    @Test
    void enforcesConfigurableMaxSteps() throws Exception {
        TestNode end = new TestNode("end", null, null, true);
        TestNode c = new TestNode("c", null, null, false);
        c.then(end);
        TestNode b = new TestNode("b", null, null, false);
        b.then(c);
        TestNode a = new TestNode("a", null, null, false);
        a.then(b);

        // a、b、c、end 共 4 个节点：上限 3 应在处理第 4 个节点前失败
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FlowEngine.run(a, "x", new ArrayList<>(), 3));
        assertTrue(e.getMessage().contains("步数上限"), e.getMessage());
        assertTrue(e.getMessage().contains("a -> b -> c"), e.getMessage());

        // 上限放宽后正常执行
        List<String> context = new ArrayList<>();
        String result = FlowEngine.run(a, "x", context, 4);
        assertEquals("end->result", result);
        assertEquals(Arrays.asList("a", "b", "c", "end"), context);
    }

    @Test
    void rejectsNonPositiveMaxSteps() {
        TestNode end = new TestNode("end", null, null, true);

        assertThrows(IllegalArgumentException.class,
                () -> FlowEngine.run(end, "x", new ArrayList<>(), 0));
    }
}
