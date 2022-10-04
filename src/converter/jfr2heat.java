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
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/**
 * Converts .jfr output produced by async-profiler to HTML Heatmap.
 */
public class jfr2heat {

    private final JfrReader jfr;

    public jfr2heat(JfrReader jfr) {
        this.jfr = jfr;
    }

    public void convert(SimpleHeatmap heatmap) throws IOException {
        for (Event event; (event = jfr.readEvent()) != null; ) {
            heatmap.addEvent(event);
        }
        heatmap.finish(
                jfr.stackTraces,
                jfr.methods,
                jfr.classes,
                jfr.symbols,
                TimeUnit.NANOSECONDS.toMillis(jfr.startNanos),
                jfr.startTicks,
                jfr.ticksPerSec
        );
    }

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

        SimpleHeatmap fg = new SimpleHeatmap("Heatmap, " + (alloc ? "Alloc" : "CPU"), alloc);
        try (JfrReader jfr = new JfrReader(input)) {
            new jfr2heat(jfr).convert(fg);
        }

        if (output == null) {
            fg.dump(System.out);
        } else {
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(output), 1024 * 1024);
                 PrintStream out = new PrintStream(bos, false, "UTF-8")) {
                fg.dump(out);
            }
        }

    }
}
