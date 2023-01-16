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

import one.jfr.JfrReader;
import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.ExecutionSample;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Converts .jfr output produced by async-profiler to HTML Heatmap.
 */
public class jfr2heat {

    public static void main(String[] args) throws Exception {

        String input = null;
        String output = null;
        boolean alloc = false;

        for (String arg : args) {
            if (arg.equals("--alloc")) {
                alloc = true;
            } else if (input == null) {
                input = arg;
            } else {
                output = arg;
            }
        }

        long startNanos = Long.MAX_VALUE;

        long startTime = System.nanoTime();
        final Heatmap heatmap = new Heatmap("Heatmap, " + (alloc ? "Alloc" : "CPU"), 20);
        for (String file : input.split(",")) {
            heatmap.nextFile();

            final JfrState state = new JfrState();
            try (JfrReader jfr = new JfrReader(file) {

                @Override
                protected void readThreads(boolean hasGroup) {
                    int count = getVarint();
                    for (int i = 0; i < count; i++) {
                        skipVarlong();
                        skipString();
                        skipVarint();
                        skipString();
                        skipVarlong();
                        if (hasGroup) skipVarlong();
                    }
                }

                @Override
                protected void readStackTraces() {
                    state.tracesPos = filePosition + buf.position();
                    int count = getVarint();
                    for (int j = 0; j < count; j++) {
                        skipVarint();
                        skipVarint();
                        int depth = getVarint();
                        for (int i = 0; i < depth; i++) {
                            skipVarlong();
                            skipVarint();
                            skipVarint();
                            buf.get();
                        }
                    }
                    state.tracesEndPos = filePosition + buf.position();
                }

                @Override
                protected boolean readConstantPool(long cpOffset) throws IOException {
                    state.tracesPos = -1;
                    boolean result = super.readConstantPool(cpOffset);
                    heatmap.assignConstantPool(methods, classes, symbols);
                    if (state.tracesPos != -1) {
                        seek(state.tracesPos);
                        ensureBytes((int) (state.tracesEndPos - state.tracesPos));

                        int count = getVarint();
                        for (int j = 0; j < count; j++) {
                            int id = getVarint();
                            skipVarint();

                            int depth = getVarint();
                            if (depth > state.maxDepth) {
                                state.maxDepth = depth + 256;
                                state.methods = new long[state.maxDepth];
                                state.types = new byte[state.maxDepth];
                                state.locations = new int[state.maxDepth];
                            }

                            for (int i = 0; i < depth; i++) {
                                state.methods[i] = getVarlong();
                                int line = getVarint();
                                int bci = getVarint();
                                state.locations[i] = line << 16 | (bci & 0xffff);
                                state.types[i] = buf.get();
                            }

                            heatmap.addStack(id, state.methods, state.locations, state.types, depth);
                        }
                    }
                    return result;
                }

                @Override
                protected ExecutionSample readExecutionSample() {
                    long time = getVarlong();
                    long msFromStart = (time - startTicks) * 1000 / ticksPerSec;
                    long msStart = TimeUnit.NANOSECONDS.toMillis(startNanos);

                    skipVarint();
                    int stackTraceId = getVarint();

                    heatmap.addEvent(stackTraceId, 0, (byte)0, msFromStart + msStart);
                    return null;
                }

                @Override
                protected AllocationSample readAllocationSample(boolean tlab) {
                    long time = getVarlong();
                    long msFromStart = (time - startTicks) * 1000 / ticksPerSec;
                    long msStart = TimeUnit.NANOSECONDS.toMillis(startNanos);

                    skipVarint();
                    int stackTraceId = getVarint();
                    int classId = getVarint();

                    heatmap.addEvent(stackTraceId, classId, (byte) (tlab ? 2 : 3), msFromStart + msStart);
                    return null;
                }

                @Override
                protected ContendedLock readContendedLock(boolean hasTimeout) {
                    long time = getVarlong();
                    skipVarlong();
                    long msFromStart = (time - startTicks) * 1000 / ticksPerSec;
                    long msStart = TimeUnit.NANOSECONDS.toMillis(startNanos);

                    skipVarint();
                    int stackTraceId = getVarint();
                    int classId = getVarint();

                    heatmap.addEvent(stackTraceId, classId, (byte) 5, msFromStart + msStart);
                    return null;
                }
/*
                @Override
                protected boolean readMeta(long metaOffset) throws IOException {
                    seek(metaOffset);
                    if (!ensureBytes(5)) {
                        return false;
                    }
                    seek(metaOffset + getVarint());
                    return true;
                }
*/
            }) {
                while (jfr.readEvent(alloc ? AllocationSample.class : ExecutionSample.class) != null) {
                    // keep reading
                }

                startNanos = Math.min(jfr.startNanos, startNanos);
            }
        }

        heatmap.finish(TimeUnit.NANOSECONDS.toMillis(startNanos));

        System.out.println("Events added in sec: " + (System.nanoTime() - startTime) / 1e9);

        if (output == null) {
            heatmap.dump(System.out);
        } else {
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(output), 1024 * 1024)) {
                heatmap.dump(bos);
            }
        }

        System.out.println("Total time in sec: " + (System.nanoTime() - startTime) / 1e9);
    }

    private static class JfrState {
        int maxDepth = 1024;
        long[] methods = new long[maxDepth];
        byte[] types = new byte[maxDepth];
        int[] locations = new int[maxDepth];

        long tracesPos = -1;
        long tracesEndPos = -1;
    }
}
