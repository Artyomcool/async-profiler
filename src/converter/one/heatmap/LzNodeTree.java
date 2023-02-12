package one.heatmap;

import java.util.Arrays;

public class LzNodeTree {

    private static final int INITIAL_CAPACITY = 2 * 1024 * 1024;

    // hash(methodId << 32 | parentNodeId) -> methodId << 32 | parentNodeId
    private long[] keys;    // reused by SynonymTable
    // hash(methodId << 32 | parentNodeId) -> childNodeId
    private int[] values;  // TODO can be reused after buildLz78TreeAndPrepareData

    // (nodeId - 1) -> methodId << 32 | parentNodeId
    private long[] outputData;              // TODO can be reused after writeTree:130!
    // nodeId -> childrenCount
    private int[] childrenCount;    // reused by SynonymTable
    // nodeId -> lengthToRoot << 32 | NOT_VISITED_MARKER | parentNodeId
    private int[] lengthToRoot;

    private int storageSize = 1;
    private int nodesCount = 1;

    public LzNodeTree() {
        keys = new long[INITIAL_CAPACITY];
        values = new int[INITIAL_CAPACITY];

        outputData = new long[INITIAL_CAPACITY / 2];
        childrenCount = new int[INITIAL_CAPACITY / 2];
        lengthToRoot = new int[INITIAL_CAPACITY / 2];
    }

    public int appendChild(int parentNode, int methodId) {
        long method = (long) methodId << 32;
        long key = method | parentNode;

        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (true) {
            long k = keys[i];
            if (k == 0) {
                break;
            }
            if (k == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }

        if (nodesCount >= outputData.length) {
            outputData = Arrays.copyOf(outputData, nodesCount + nodesCount / 2);
            childrenCount = Arrays.copyOf(childrenCount, nodesCount + nodesCount / 2);
            lengthToRoot =  Arrays.copyOf(lengthToRoot, nodesCount + nodesCount / 2);
        }

        lengthToRoot[nodesCount] = lengthToRoot[parentNode] + 1;
        outputData[nodesCount - 1] = key;
        keys[i] = key;
        values[i] = nodesCount;

        if (nodesCount * 2 > keys.length) {
            resize(keys.length * 2);
        }
        nodesCount++;

        childrenCount[parentNode]--; // negotiation for better sort

        return 0;
    }

    public long[] treeData() {
        return outputData;
    }

    public int treeDataSize() {
        return nodesCount - 1;
    }

    public int extractParentId(long treeElement) {
        return (int) treeElement;
    }

    public int extractMethodId(long treeElement) {
        return (int) (treeElement >>> 32);
    }

    // destroys keys and childrenCount arrays
    public SynonymTable extractSynonymTable() {
        for (int i = 0; i < nodesCount; i++) {
            if (childrenCount[i] != 0) {
                lengthToRoot[i] = 0;
            }
        }
        return new SynonymTable(keys, childrenCount, nodesCount);
    }

    public void markUsed(int nodeId) {
        storageSize += lengthToRoot[nodeId];
        lengthToRoot[nodeId] = 0;
    }

    public int storageSize() {
        System.out.println("storageSize: " + storageSize);
        return storageSize;
    }

    public int nodesCount() {
        return nodesCount;
    }

    private void resize(int newCapacity) {
        long[] newKeys = new long[newCapacity];
        int[] newValues = new int[newCapacity];
        int mask = newKeys.length - 1;

        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != 0) {
                for (int j = hashCode(keys[i]) & mask; ; j = (j + 1) & mask) {
                    if (newKeys[j] == 0) {
                        newKeys[j] = keys[i];
                        newValues[j] = values[i];
                        break;
                    }
                }
            }
        }

        keys = newKeys;
        values = newValues;
    }

    private static int hashCode(long key) {
        key *= 0xc6a4a7935bd1e995L;
        return (int) (key ^ (key >>> 32));
    }
}
