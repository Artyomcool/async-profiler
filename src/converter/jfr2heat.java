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
import one.jfr.event.Event;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
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

        SimpleHeatmap heatmap = new SimpleHeatmap("Heatmap, " + (alloc ? "Alloc" : "CPU"), alloc);
        for (String file : input.split(",")) {
            try (JfrReader jfr = new JfrReader(file)) {
                heatmap.nextFile(
                        jfr.stackTraces,
                        jfr.methods,
                        jfr.classes,
                        jfr.symbols
                );

                for (Event event; (event = jfr.readEvent()) != null; ) {
                    long msFromStart = (event.time - jfr.startTicks) * 1000 / jfr.ticksPerSec;
                    long msStart = TimeUnit.NANOSECONDS.toMillis(jfr.startNanos);
                    heatmap.addEvent(event, msFromStart + msStart);
                }

                startNanos = Math.min(jfr.startNanos, startNanos);
            }
        }

        heatmap.finish(TimeUnit.NANOSECONDS.toMillis(startNanos));

        if (output == null) {
            heatmap.dump(System.out);
        } else {
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(output), 1024 * 1024);
                 PrintStream out = new PrintStream(bos, false, "UTF-8")) {
                heatmap.dump(out);
            }
        }

    }
}
