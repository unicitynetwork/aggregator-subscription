package org.unicitylabs.proxy.shard;

/**
 * A node in the binary routing tree for shard lookup optimization.
 * Each node represents a decision point based on a bit in the Request ID suffix.
 *
 * The tree is traversed from least significant bit (LSB) to most significant bit (MSB).
 * A leaf node (one with a non-null targetUrl) represents a shard destination.
 */
public class ShardTreeNode {
    private ShardTreeNode left;   // Child for bit value 0
    private ShardTreeNode right;  // Child for bit value 1
    private String targetUrl;     // Non-null only for leaf nodes

    public ShardTreeNode() {
        this.left = null;
        this.right = null;
        this.targetUrl = null;
    }

    public ShardTreeNode getLeft() {
        return left;
    }

    public void setLeft(ShardTreeNode left) {
        this.left = left;
    }

    public ShardTreeNode getRight() {
        return right;
    }

    public void setRight(ShardTreeNode right) {
        this.right = right;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    /**
     * Check if this node is a leaf (has a target URL).
     */
    public boolean isLeaf() {
        return targetUrl != null;
    }
}
