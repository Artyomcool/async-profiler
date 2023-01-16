import java.util.Map;
import java.util.TreeMap;

class Histogram {
    private static final int COUNT = 1000;
    Map<String, int[]> data = new TreeMap<>();

    void add(int value, String name) {
        int[] histogram = data.get(name);
        if (histogram == null) {
            histogram = new int[COUNT + 1];
            data.put(name, histogram);
        }

        value = value + 1;
        if (value >= histogram.length || value < 0) {
            histogram[0]++;
        } else {
            histogram[value]++;
        }
        histogram[COUNT]++;
    }

    void print() {
        for (Map.Entry<String, int[]> kv : data.entrySet()) {
            String name = kv.getKey();
            int[] histogram = kv.getValue();

            int count = 0;
            for (int i = 0; i < histogram.length - 1; i++) {
                if (histogram[i] != 0) {
                    count = i + 1;
                }
            }
            System.out.println("Histogram " + name + ": ");
            int nowCount = 0;
            for (int i = 1; i < count; i++) {
                int c = histogram[i];
                nowCount += c;
                System.out.printf("%4d: %d (%.3f) [%.3f]\n", i - 1, c, c / (double) histogram[COUNT], nowCount / (double) histogram[COUNT]);
            }
            if (histogram[0] != 0) {
                int c = histogram[0];
                nowCount += c;
                System.out.printf(">%3d: %d (%.3f) [%.3f]\n", COUNT - 1, c, c / (double) histogram[COUNT], nowCount / (double) histogram[COUNT]);
            }
            System.out.println();
        }
    }
}
