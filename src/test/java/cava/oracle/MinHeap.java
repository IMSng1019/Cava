package cava.oracle;

/**
 * 原版 net.minecraft.entity.ai.pathing.PathMinHeap（class_5 / efb）的逐指令复刻。
 *
 * <p>关键点（规格第 1 节，证据为 javap -p -c）：
 * <ul>
 *   <li>shiftUp 用**严格 &lt;**，相等不上移；</li>
 *   <li>shiftDown 在左右孩子相等时选**右孩子**；右孩子不存在时按 +Infinity 参与比较；</li>
 *   <li>pop() 取下标 0，把**最后一个元素**搬到根部；</li>
 *   <li>clear() **只置 count = 0**，不重置数组、不重置 heapIndex；</li>
 *   <li>push 扩容是 count &lt;&lt; 1，并对已在堆中的节点抛 IllegalStateException("OW KNOWS!")。</li>
 * </ul>
 */
public final class MinHeap {
    private PNode[] pathNodes = new PNode[128];
    private int count;

    public PNode push(PNode node) {
        if (node.heapIndex >= 0) {
            throw new IllegalStateException("OW KNOWS!");
        }
        if (this.count == this.pathNodes.length) {
            PNode[] grown = new PNode[this.count << 1];
            System.arraycopy(this.pathNodes, 0, grown, 0, this.count);
            this.pathNodes = grown;
        }
        this.pathNodes[this.count] = node;
        node.heapIndex = this.count;
        shiftUp(this.count++);
        return node;
    }

    public void clear() {
        this.count = 0;
    }

    public PNode getStart() {
        return this.pathNodes[0];
    }

    public PNode pop() {
        PNode node = this.pathNodes[0];
        this.pathNodes[0] = this.pathNodes[--this.count];
        this.pathNodes[this.count] = null;
        if (this.count > 0) {
            shiftDown(0);
        }
        node.heapIndex = -1;
        return node;
    }

    public void popNode(PNode node) {
        this.pathNodes[node.heapIndex] = this.pathNodes[--this.count];
        this.pathNodes[this.count] = null;
        if (this.count > node.heapIndex) {
            if (this.pathNodes[node.heapIndex].heapWeight < node.heapWeight) {
                shiftUp(node.heapIndex);
            } else {
                shiftDown(node.heapIndex);
            }
        }
        node.heapIndex = -1;
    }

    public void setNodeWeight(PNode node, float weight) {
        float old = node.heapWeight;
        node.heapWeight = weight;
        if (weight < old) {
            shiftUp(node.heapIndex);
        } else {
            shiftDown(node.heapIndex);
        }
    }

    public int getCount() {
        return this.count;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    private void shiftUp(int index) {
        PNode node = this.pathNodes[index];
        float weight = node.heapWeight;
        while (index > 0) {
            int parent = (index - 1) >> 1;
            PNode parentNode = this.pathNodes[parent];
            if (weight < parentNode.heapWeight) {
                this.pathNodes[index] = parentNode;
                parentNode.heapIndex = index;
                index = parent;
            } else {
                break;
            }
        }
        this.pathNodes[index] = node;
        node.heapIndex = index;
    }

    private void shiftDown(int index) {
        PNode node = this.pathNodes[index];
        float weight = node.heapWeight;
        while (true) {
            int child = 1 + (index << 1);
            int sibling = child + 1;
            if (child >= this.count) {
                break;
            }
            PNode childNode = this.pathNodes[child];
            float childWeight = childNode.heapWeight;
            PNode siblingNode;
            float siblingWeight;
            if (sibling >= this.count) {
                siblingNode = null;
                siblingWeight = Float.POSITIVE_INFINITY;
            } else {
                siblingNode = this.pathNodes[sibling];
                siblingWeight = siblingNode.heapWeight;
            }
            if (childWeight < siblingWeight) {
                if (childWeight < weight) {
                    this.pathNodes[index] = childNode;
                    childNode.heapIndex = index;
                    index = child;
                } else {
                    break;
                }
            } else {
                if (siblingWeight < weight) {
                    this.pathNodes[index] = siblingNode;
                    siblingNode.heapIndex = index;
                    index = sibling;
                } else {
                    break;
                }
            }
        }
        this.pathNodes[index] = node;
        node.heapIndex = index;
    }
}
