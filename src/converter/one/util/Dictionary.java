/*
 * Copyright 2020 Andrei Pangin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package one.util;

import java.util.Arrays;

/**
 * Fast and compact long->Object map.
 */
public class Dictionary<T> {
    private static final int INITIAL_CAPACITY = 16;

    private long[] keys;
    private Object[] values;
    private int size;

    public Dictionary() {
        this(INITIAL_CAPACITY);
    }

    public Dictionary(int capacity) {
        this.keys = new long[capacity];
        this.values = new Object[capacity];
    }

    public void clear() {
        Arrays.fill(keys, 0);
        Arrays.fill(values, null);
        size = 0;
    }

    public void put(long key, T value) {
        if (key == 0) {
            throw new IllegalArgumentException("Zero key not allowed");
        }

        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (keys[i] != 0) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = value;

        if (++size * 2 > keys.length) {
            resize(keys.length * 2);
        }
    }

    @SuppressWarnings("unchecked")
    public T get(long key) {
        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (keys[i] != key && keys[i] != 0) {
            i = (i + 1) & mask;
        }
        return (T) values[i];
    }

    @SuppressWarnings("unchecked")
    public T getOrDefault(long key, T def) {
        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (keys[i] != key) {
            if (keys[i] == 0) {
                return def;
            }
            i = (i + 1) & mask;
        }
        return (T) values[i];
    }

    @SuppressWarnings("unchecked")
    public T getFirst() {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != 0) {
                return (T) values[i];
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public void forEach(Visitor<T> visitor) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != 0) {
                visitor.visit(keys[i], (T) values[i]);
            }
        }
    }

    public int preallocate(int count) {
        if (count * 2 > keys.length) {
            resize(Integer.highestOneBit(count * 4 - 1));
        }
        return count;
    }

    public int size() {
        return size;
    }

    private void resize(int newCapacity) {
        long[] newKeys = new long[newCapacity];
        Object[] newValues = new Object[newCapacity];
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

    public interface Visitor<T> {
        void visit(long key, T value);
    }
}
