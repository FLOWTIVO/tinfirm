package com.flowtivo.tinfirm.vault.internal;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;

/**
 * Aho-Corasick over bytes: finds which of many byte patterns occur in some content, in one pass. Each pattern
 * carries a label (an index chosen by the caller); {@link #find} returns the labels that occurred.
 * <p>
 * The root has a dense transition table; the other nodes, which mostly have one child, keep small arrays.
 */
final class ByteMatcher {

    private final Node root = new Node();

    private final Node[] rootNext = new Node[256];

    private final int labelCount;

    /**
     * @param patterns   the patterns; empty ones are ignored
     * @param labels     the label of each pattern, from 0 to {@code labelCount - 1}
     * @param labelCount the number of distinct labels
     */
    ByteMatcher(List<byte[]> patterns, int[] labels, int labelCount) {
        if (patterns.size() != labels.length) {
            throw new IllegalArgumentException("One label per pattern");
        }
        this.labelCount = labelCount;
        for (int i = 0; i < patterns.size(); i++) {
            add(patterns.get(i), labels[i]);
        }
        link();
    }

    /** The labels of the patterns that occur in {@code content}. Stops early once every label was found. */
    BitSet find(byte[] content) {
        BitSet found = new BitSet(labelCount);
        pass(found).feed(content, 0, content.length);
        return found;
    }

    /** Receives an occurrence of a pattern: its label, and where it starts. */
    @FunctionalInterface
    interface Hit {

        void at(int label, int start);
    }

    /** Calls {@code hit} for every occurrence of a pattern in {@code content[from, to)}, in the order of their ends. */
    void forEach(byte[] content, int from, int to, Hit hit) {
        Node node = root;
        for (int i = from; i < to; i++) {
            node = step(node, content[i]);
            for (Node out = node.labels.length > 0 ? node : node.dictionary; out != null; out = out.dictionary) {
                for (int label : out.labels) {
                    hit.at(label, i + 1 - out.depth);
                }
            }
        }
    }

    /** A pass over content that arrives in parts, such as a member of an archive as it is unpacked. */
    Pass pass(BitSet found) {
        return new Pass(found);
    }

    /**
     * One pass over content fed in parts, in order: a pattern cut between two parts is found as in one piece. The
     * labels found go into the given set, which several passes may share; feeding stops once every label is in it.
     */
    final class Pass {

        private final BitSet found;

        private Node node = root;

        private Pass(BitSet found) {
            this.found = found;
        }

        void feed(byte[] content, int offset, int length) {
            if (labelCount == 0 || found.cardinality() == labelCount) {
                return;
            }
            for (int i = offset; i < offset + length; i++) {
                node = step(node, content[i]);
                Node out = node.labels.length > 0 ? node : node.dictionary;
                if (out == null) {
                    continue;
                }
                for (; out != null; out = out.dictionary) {
                    for (int label : out.labels) {
                        found.set(label);
                    }
                }
                if (found.cardinality() == labelCount) {
                    return;
                }
            }
        }
    }

    private Node step(Node from, byte value) {
        Node node = from;
        while (true) {
            Node next = node == root ? rootNext[value & 0xff] : node.child(value);
            if (next != null) {
                return next;
            }
            if (node == root) {
                return root;
            }
            node = node.fail;
        }
    }

    private void add(byte[] pattern, int label) {
        if (pattern.length == 0) {
            return;
        }
        Node node = root;
        for (byte value : pattern) {
            Node next = node == root ? rootNext[value & 0xff] : node.child(value);
            if (next == null) {
                next = new Node();
                next.depth = node.depth + 1;
                if (node == root) {
                    rootNext[value & 0xff] = next;
                }
                else {
                    node.addChild(value, next);
                }
            }
            node = next;
        }
        if (Arrays.stream(node.labels).noneMatch(existing -> existing == label)) {
            node.labels = Arrays.copyOf(node.labels, node.labels.length + 1);
            node.labels[node.labels.length - 1] = label;
        }
    }

    /** Breadth-first: failure links, and dictionary links to the nearest failure ancestor with labels. */
    private void link() {
        Deque<Node> queue = new ArrayDeque<>();
        for (Node child : rootNext) {
            if (child != null) {
                child.fail = root;
                queue.add(child);
            }
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (int i = 0; i < node.size; i++) {
                byte value = node.keys[i];
                Node child = node.next[i];
                child.fail = step(node.fail, value);
                child.dictionary = child.fail.labels.length > 0 ? child.fail : child.fail.dictionary;
                queue.add(child);
            }
        }
    }

    private static final class Node {

        private byte[] keys = new byte[1];

        private Node[] next = new Node[1];

        private int size;

        private int[] labels = new int[0];

        /** The length of the bytes from the root to this node. */
        private int depth;

        private Node fail;

        private Node dictionary;

        Node child(byte value) {
            for (int i = 0; i < size; i++) {
                if (keys[i] == value) {
                    return next[i];
                }
            }
            return null;
        }

        void addChild(byte value, Node child) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                next = Arrays.copyOf(next, size * 2);
            }
            keys[size] = value;
            next[size] = child;
            size++;
        }
    }
}
