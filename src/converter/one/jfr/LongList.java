package one.jfr;

import java.util.Arrays;

public class LongList {

    public long[] list = new long[128];
    public int size;

    public void add(long i) {
        if (size == list.length) {
            list = Arrays.copyOf(list, list.length * 3 / 2);
        }
        list[size++] = i;
    }

}
