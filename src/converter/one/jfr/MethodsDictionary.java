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

package one.jfr;

/**
 * Fast and compact long->Object map.
 */
public class MethodsDictionary {
/*
    private static final int INITIAL_CAPACITY = 1024 * 1024;

    private Dictionary<MethodRef> methodRefs;
    private Dictionary<ClassRef> classRefs;
    private Dictionary<byte[]> symbols;

    private long[] packedMethodsData;
    private int size = 0;

    public MethodsDictionary() {
        this(INITIAL_CAPACITY);
    }

    public MethodsDictionary(int capacity) {
        this.packedMethodsData = new long[capacity * 4];
    }

    public int getOrCreate(long originalMethodId, byte type, int location, boolean start) {
        int mask = packedMethodsData.length / 4 - 1;
        int i = hashCode(originalMethodId) & mask;
        while (true) {
            int pos = i * 4;
            long originalMethod = packedMethodsData[pos];
            if (originalMethod == 0) {
                break;
            }
            if (originalMethod != originalMethodId) {
                i = (i + 1) & mask;
                continue;
            }

            int t = pos;

        }
        keys[i] = key;
        values[i] = value;

        if (++size * 2 > keys.length) {
            resize(keys.length * 2);
        }
    }

    @SuppressWarnings("unchecked")
    public T putIfAbsent(long key, T value) {
        if (key == 0) {
            throw new IllegalArgumentException("Zero key not allowed");
        }

        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (keys[i] != 0) {
            if (keys[i] == key) {
                return (T) values[i];
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = value;

        if (++size * 2 > keys.length) {
            resize(keys.length * 2);
        }
        return null;
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
    public T getOrCreate(long key, Initializer<? extends T> initializer) {
        if (key == 0) {
            throw new IllegalArgumentException("Zero key not allowed");
        }

        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (keys[i] != 0) {
            if (keys[i] == key) {
                return (T) values[i];
            }
            i = (i + 1) & mask;
        }
        T result = initializer.createForEmpty();
        keys[i] = key;
        values[i] = result;

        if (++size * 2 > keys.length) {
            resize(keys.length * 2);
        }
        return result;
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

    public interface Initializer<T> {
        T createForEmpty();
    }
*/
}
