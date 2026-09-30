package com.hmdp.framework.rule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 规则树引擎单测：验证顺序执行、分支跳转、终点收口与防环保护。
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

        assertThrows(IllegalStateException.class, () -> FlowEngine.run(node, "x", new ArrayList<>()));
    }
}
