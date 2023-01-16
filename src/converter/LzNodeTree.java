import java.util.Arrays;

public class LzNodeTree {

    private static final int INITIAL_CAPACITY = 2 * 1024 * 1024;

    private long[] keys;
    private int[] values;
    private int size;

    private long[] outputData;  // methodId << 32 | parentNode
    private int[] nodeCounts;
    private long[] parentsWithSizes;
    private int nodesCount = 1;

    private int synonymsCount;

    public LzNodeTree() {
        keys = new long[INITIAL_CAPACITY];
        values = new int[INITIAL_CAPACITY];

        outputData = new long[INITIAL_CAPACITY / 2];
        nodeCounts = new int[INITIAL_CAPACITY / 2];
        parentsWithSizes = new long[INITIAL_CAPACITY / 2];
        parentsWithSizes[0] = 0x8000_0000_0000_0000L;
    }

    public int putIfAbsent(int parentNode, int methodId) {
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
            nodeCounts = Arrays.copyOf(nodeCounts, nodesCount + nodesCount / 2);
            parentsWithSizes =  Arrays.copyOf(parentsWithSizes, nodesCount + nodesCount / 2);
        }

        int parentSize = (int) (parentsWithSizes[parentNode] >>> 32);
        parentsWithSizes[nodesCount] = (long)(parentSize + 1) << 32 | parentNode;
        outputData[nodesCount - 1] = key;
        keys[i] = key;
        values[i] = nodesCount++;

        if (++size * 2 > keys.length) {
            resize(keys.length * 2);
        }

        nodeCounts[parentNode]--; // negotiation for better sort

        return 0;
    }

    public void calculateSynonyms() {
        long[] synonyms = keys; // keys and values are always greater then node counts
        for (int i = 0; i < nodesCount; i++) {
            synonyms[i] = (long) nodeCounts[i] << 32 | i;
        }
        Arrays.sort(synonyms, 0, nodesCount);

        synonymsCount = Math.min(61 * 61, nodesCount);

        int[] nodesIds = nodeCounts;
        for (int i = 0; i < nodesIds.length; i++) {
            nodesIds[i] = synonymsCount + i;
        }
        for (int i = 0; i < synonymsCount; i++) {
            nodesIds[(int) (synonyms[i] & 0xFFFFFFFFL)] = i;
        }
    }

    public int[] nodesCounts() {
        return nodeCounts;
    }

    public long[] nodesParentsWithSizes() {
        return parentsWithSizes;
    }

    public int nodeIdOrSynonym(int node) {
        int[] nodesIds = nodeCounts;
        return nodesIds[node];
    }

    public long[] data() {
        return outputData;
    }

    public int synonymsCount() {
        return synonymsCount;
    }

    public int nodesCount() {
        return nodesCount;
    }

    public void visitTreeNodeSynonyms(SynonymVisitor visitor) {
        for (int i = 0; i < synonymsCount; i++) {
            visitor.nextSynonym((int) (keys[i] & 0xFFFFFFFFL) + synonymsCount);
        }
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

    public interface SynonymVisitor {
        void nextSynonym(int synonym);
    }

}
