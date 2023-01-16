import one.jfr.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Comparator;

public class Heatmap extends ResourceProcessor {

    private final SampleList sampleList;
    private final StackStorage stackTracesRemap = new StackStorage();

    private final String title;

    private final DictionaryInt stackTracesCache = new DictionaryInt();
    private final MethodCache methodsCache = new MethodCache();

    private long startMs;

    public Heatmap(String title, long blockDurationMs) {
        this.title = title;

        this.sampleList = new SampleList(blockDurationMs);
    }

    public void assignConstantPool(
            Dictionary<MethodRef> methodRefs,
            Dictionary<ClassRef> classRefs,
            Dictionary<byte[]> symbols
    ) {
        methodsCache.assignConstantPool(methodRefs, classRefs, symbols);
    }

    public void nextFile() {
        stackTracesCache.clear();
        methodsCache.clear();
    }

    public void addEvent(int stackTraceId, int extra, byte type, long timeMs) {
        if (extra == 0) {
            sampleList.add(stackTracesCache.get(stackTraceId), timeMs);
            return;
        }

        int id = stackTracesCache.get((long) extra << 32 | stackTraceId, -1);
        if (id != -1) {
            sampleList.add(id, timeMs);
            return;
        }

        int prototypeId = stackTracesCache.get(stackTraceId);
        int[] prototype = stackTracesRemap.get(prototypeId);

        id = stackTracesRemap.indexWithPrototype(prototype, methodsCache.indexForClass(extra, type));
        stackTracesCache.put((long) extra << 32 | stackTraceId, id);

        sampleList.add(id, timeMs);
    }

    private int[] cachedStackTrace = new int[4096];

    public void addStack(int id, long[] methods, int[] locations, byte[] types, int size) {
        int[] stackTrace = cachedStackTrace;
        if (stackTrace.length < size) {
            cachedStackTrace = stackTrace = new int[size * 2];
        }

        for (int i = size - 1; i >= 0; i--) {
            long methodId = methods[i];
            byte type = types[i];
            int location = locations[i];

            int index = size - 1 - i;
            boolean firstMethodInTrace = index == 0;

            stackTrace[index] = methodsCache.index(methodId, location, type, firstMethodInTrace);
        }

        stackTracesCache.put(id, stackTracesRemap.index(stackTrace, size));
    }

    public void finish(long startMs) {
        this.startMs = startMs;
        assignConstantPool(null, null, null);
        nextFile();
    }

    private EvaluationContext evaluate() {
        return new EvaluationContext(
                sampleList.destoyForSamples(),
                methodsCache.methodsIndex(),
                stackTracesRemap.destroyForOrderedTraces(),
                methodsCache.orderedSymbolTable()
        );
    }

    private void compressMethods(Output out, Method[] methods) throws IOException {
        for (Method method : methods) {
            out.write18(method.className);
            out.write18(method.methodName);
            out.write18(method.location & 0xffff);
            out.write18(method.location >>> 16);
            out.write6(method.type);
        }
    }

    public void dump(OutputStream stream) throws IOException {
        HtmlOut out = new HtmlOut(stream);

        EvaluationContext evaluationContext = evaluate();

        String tail = getResource("/heatmap.html");

        tail = printTill(out, tail, "/*height:*/300");
        out.write("300".getBytes());

        tail = printTill(out, tail, "/*if heatmap css:*/");
        tail = printTill(out, tail, "/*end if heatmap css*/");

        tail = printTill(out, tail, "/*if heatmap html:*/");

        tail = printTill(out, tail, "/*executionsHeatmap:*/");
        out.resetPos();
        int was = out.pos();
        printHeatmap(out, evaluationContext);
        System.out.println("heatmap " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");

        tail = printTill(out, tail, "/*methods:*/");
        was = out.pos();
        printMethods(out, evaluationContext);
        System.out.println("methods " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");

        tail = printTill(out, tail, "/*end if heatmap html*/");
        tail = printTill(out, tail, "/*title:*/");
        out.write(title.getBytes());

        tail = printTill(out, tail, "/*if flamegraph html:*/");
        tail = skipTill(tail, "/*end if flamegraph html*/");

        tail = printTill(out, tail, "/*reverse:*/false");
        out.write("true".getBytes());

        tail = printTill(out, tail, "/*depth:*/0");
        out.write("0".getBytes());

        tail = printTill(out, tail, "/*if flamegraph js:*/");
        tail = skipTill(tail, "/*end if flamegraph js*/");

        tail = printTill(out, tail, "/*if heatmap js:*/");

        tail = printTill(out, tail, "/*startMs:*/0");
        out.write(String.valueOf(startMs).getBytes());

        tail = printTill(out, tail, "/*cpool:*/");
        was = out.pos();
        printConstantPool(out, evaluationContext);
        System.out.println("constant pool " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");

        tail = printTill(out, tail, "/*end if heatmap js*/");

        tail = printTill(out, tail, "/*frames:*/");
        out.write(tail.getBytes());

    }

    private void printHeatmap(final Output out, EvaluationContext context) throws IOException {
        Histogram histogram = new Histogram();

        int veryStart = out.pos();
        int wasPos = out.pos();

        // calculates methods frequency during building the tree
        int[] stackChunksBuffer = buildLz78TreeAndPrepareData(context);

        // gives methods new ids, more frequent (in tree's data) methods will have lower id
        renameMethodsByFrequency(context);

        // writes "starts" - ids of methods that indicates a start of a next stack trace
        writeStartMethods(out, context);
        wasPos = debugStep("start methods", out, wasPos, veryStart);

        // writes block sizes
        writeBlockSizes(out, context);
        wasPos = debugStep("stack sizes", out, wasPos, veryStart);

        // destroys internal state!
        context.nodeTree.calculateSynonyms();
        // writes frequent lz tree nodes as a synonyms table
        writeTreeSynonyms(out, context);
        wasPos = debugStep("tree synonyms", out, wasPos, veryStart);

        writeTree(out, context);
        wasPos = debugStep("tree body", out, wasPos, veryStart);

        // calculate counts for the next synonyms table, that will be used for samples
        int chunksCount = calculateAndWriteSamplesSynonyms(context, stackChunksBuffer);
        System.out.println("chunksCount " + chunksCount);
        // writes frequent lz tree nodes as a synonyms table
        writeTreeSynonyms(out, context);
        wasPos = debugStep("samples synonyms", out, wasPos, veryStart);

        // writes bodies chunks
        writeSamples(out, context, stackChunksBuffer);
        debugStep("samples body", out, wasPos, veryStart);

        // calculates amount of storage needed for unpacking samples
        long storageSize = calculateStorageSize(context);
        System.out.println("storageSize " + (storageSize >> 32) * 4 / 1024.0 / 1024.0 + " MB (" + (storageSize >> 32) + ")");
        System.out.println("uniqueChunks " + (int) storageSize);

        out.write30(context.nodeTree.nodesCount());
        out.write30(context.sampleList.blockSizes.length);
        out.write30((int) (storageSize >> 32));
        out.write30(chunksCount);
        out.write30((int) storageSize);  // unique chunks
        out.write30(context.sampleList.stackIds.length);
        System.out.println("stacksCount " + context.sampleList.stackIds.length);

        System.out.println("all data length " + (out.pos() - veryStart) / 1024.0 / 1024.0 + " MB (" + (out.pos() - veryStart) + ")");
        histogram.print();
    }

    private long calculateStorageSize(EvaluationContext context) {
        int storageSize = 0;
        int uniqueCount = 0;
        long[] parentsWithSizes = context.nodeTree.nodesParentsWithSizes();
        Arrays.sort(parentsWithSizes, 1, context.nodeTree.nodesCount());

        for (int i = context.nodeTree.nodesCount() - 1; i > 0; i--) {
            long c = parentsWithSizes[i];
            if (c < 0) {
                break;
            }
            uniqueCount++;
            if (c == 0) {
                continue;
            }

            int count = (int) (c >> 32);
            storageSize += count;

            parentsWithSizes[i] = 0;

            int p = (int) c;
            do {
                c = parentsWithSizes[p];
                parentsWithSizes[p] = 0;
                p = (int) c;
            } while (p != 0);
        }
        return (long) storageSize << 32 | uniqueCount;
    }

    private void writeSamples(Output out, EvaluationContext context, int[] stackChunksBuffer) throws IOException {
        System.out.println("writeSamples " + out.pos());
        int sample = 0;
        for (int stackId : context.sampleList.stackIds) {
            int chunksStart = stackChunksBuffer[stackId * 2];
            int chunksEnd = stackChunksBuffer[stackId * 2 + 1];

            long[] parentsWithSizes = context.nodeTree.nodesParentsWithSizes();
            for (int i = chunksStart; i < chunksEnd; i++) {
                int nodeId = stackChunksBuffer[i];
                parentsWithSizes[nodeId] &= 0x7FFF_FFFF_FFFF_FFFFL;
                out.writeVar(context.nodeTree.nodeIdOrSynonym(nodeId));
            }
            sample++;
        }
        System.out.println("sample " + sample);
    }

    private int calculateAndWriteSamplesSynonyms(EvaluationContext context, int[] stackChunksBuffer) {
        int chunksCount = 0;
        int[] nodeCounts = context.nodeTree.nodesCounts();
        Arrays.fill(nodeCounts, 0, context.nodeTree.nodesCount(), 0);

        for (int stackId : context.sampleList.stackIds) {
            int chunksStart = stackChunksBuffer[stackId * 2];
            int chunksEnd = stackChunksBuffer[stackId * 2 + 1];

            for (int i = chunksStart; i < chunksEnd; i++) {
                nodeCounts[stackChunksBuffer[i]]--; // negation for reverse sort
                chunksCount++;
            }
        }

        context.nodeTree.calculateSynonyms();
        return chunksCount;
    }

    private void writeTree(Output out, EvaluationContext context) throws IOException {
        long[] data = context.nodeTree.data();
        for (int i = 0; i < context.nodeTree.nodesCount() - 1; i++) {
            long d = data[i];
            int parentId = (int) d;
            int methodId = (int) (d >>> 32);
            out.writeVar(context.nodeTree.nodeIdOrSynonym(parentId));
            out.writeVar(context.orderedMethods[methodId - 1].frequencyOrNewMethodId);
        }
    }

    private void writeTreeSynonyms(final Output out, EvaluationContext context) throws IOException {
        out.writeVar(context.nodeTree.synonymsCount());
        context.nodeTree.visitTreeNodeSynonyms(new LzNodeTree.SynonymVisitor() {
            @Override
            public void nextSynonym(int synonym) {
                try {
                    out.writeVar(synonym);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private int debugStep(String step, Output out, int wasPos, int veryStartPos) {
        int nowPos = out.pos();
        System.out.println(step + " " + (nowPos - wasPos) / (1024.0 * 1024.0) + " MB");
        System.out.println(step + " pos in data " + (nowPos - veryStartPos));
        return nowPos;
    }

    private void writeStartMethods(Output out, EvaluationContext context) throws IOException {
        int startsCount = 0;
        for (Method method : context.orderedMethods) {
            if (method.start) {
                startsCount++;
            }
        }
        out.writeVar(startsCount);
        for (Method method : context.orderedMethods) {
            if (method.start) {
                out.writeVar(method.frequencyOrNewMethodId);
            }
        }
        System.out.println("start methods count " + startsCount);
    }

    private void renameMethodsByFrequency(EvaluationContext context) {
        Arrays.sort(context.orderedMethods, new Comparator<Method>() {
            @Override
            public int compare(Method o1, Method o2) {
                return Integer.compare(o2.frequencyOrNewMethodId, o1.frequencyOrNewMethodId);
            }
        });

        for (int i = 0; i < context.orderedMethods.length; i++) {
            Method method = context.orderedMethods[i];
            method.frequencyOrNewMethodId = i + 1; // zero is reserved for no method
        }

        // restores order
        context.methods.orderedKeys(context.orderedMethods);
    }

    private int[] buildLz78TreeAndPrepareData(EvaluationContext context) {
        int[] samples = context.sampleList.stackIds;

        // prepared data for output, firstly used to remember last stack positions
        int[] stackBuffer = new int[(context.stackTraces.length + 1) * 16];
        // remember the last position of stackId
        for (int i = 0; i < samples.length; i++) {
            int stackId = samples[i];
            stackBuffer[stackId * 2] = ~i;   // rewrites data multiple times, the last one wins
        }

        int chunksIterator = context.stackTraces.length * 2 + 1;

        // builds the tree and prepares data for the last stack
        for (int i = 0; i < samples.length; i++) {
            int stackId = samples[i];
            int current = 0;
            int[] stack = context.stackTraces[stackId];

            if (i == ~stackBuffer[stackId * 2]) {    // last version of that stack
                stackBuffer[stackId * 2] = chunksIterator;  // start

                for (int methodId : stack) {
                    current = context.nodeTree.putIfAbsent(current, methodId);
                    if (current == 0) { // so we are starting from root again, it will be written to output as Lz78 element - [parent node id; method id]
                        context.orderedMethods[methodId - 1].frequencyOrNewMethodId++;
                        if (stackBuffer.length == chunksIterator) {
                            stackBuffer = Arrays.copyOf(stackBuffer, chunksIterator + chunksIterator / 2);
                        }
                        stackBuffer[chunksIterator++] = context.nodeTree.nodesCount() - 1;  // TODO encapsulate
                    }
                }

                if (current != 0) {
                    if (stackBuffer.length == chunksIterator) {
                        stackBuffer = Arrays.copyOf(stackBuffer, chunksIterator + chunksIterator / 2);
                    }
                    stackBuffer[chunksIterator++] = current;
                }

                stackBuffer[stackId * 2 + 1] = chunksIterator;  // end
            } else { // general case
                for (int methodId : stack) {
                    current = context.nodeTree.putIfAbsent(current, methodId);
                    if (current == 0) { // so we are starting from root again, it will be written to output as Lz78 element - [parent node id; method id]
                        context.orderedMethods[methodId - 1].frequencyOrNewMethodId++;
                    }
                }
            }
        }

        return stackBuffer;
    }

    private void writeBlockSizes(Output out, EvaluationContext context) throws IOException {
        int[] blockSizeFrequencies = new int[1024];
        int maxBlockSize = 0;
        for (int blockSize : context.sampleList.blockSizes) {
            if (blockSize >= blockSizeFrequencies.length) {
                blockSizeFrequencies = Arrays.copyOf(blockSizeFrequencies, blockSize * 2);
            }
            blockSizeFrequencies[blockSize]++;
            maxBlockSize = Math.max(maxBlockSize, blockSize);
        }

        HuffmanEncoder encoder = new HuffmanEncoder(blockSizeFrequencies, maxBlockSize);

        long[] decodeTable = encoder.calculateOutputTable();

        out.writeVar(decodeTable.length);
        int maxBits = (int) (decodeTable[decodeTable.length - 1] >>> 56);
        out.writeVar(maxBits);

        // TODO encapsulate
        for (long l : decodeTable) {
            out.writeVar(l & 0x00FF_FFFF_FFFF_FFFFL);
            out.writeVar(l >>> 56);
        }

        int i = 0;
        for (int blockSize : context.sampleList.blockSizes) {
            if (encoder.append(blockSize)) {
                for (int value : encoder.values) {
                    out.nextByte(value);
                }
            }
        }
        if (encoder.flush()) {
            for (int value : encoder.values) {
                out.nextByte(value);
            }
        }
    }

    private void printConstantPool(Output out, EvaluationContext evaluationContext) throws IOException {
        for (byte[] symbol : evaluationContext.symbols) {
            out.nextByte('"');
            out.write(symbol);
            out.nextByte('"');
            out.nextByte(',');
        }
    }

    private void printMethods(Output out, EvaluationContext evaluationContext) throws IOException {
        System.out.println("methods count " + evaluationContext.orderedMethods.length);
        Arrays.sort(evaluationContext.orderedMethods, new Comparator<Method>() {
            @Override
            public int compare(Method o1, Method o2) {
                return Integer.compare(o1.frequencyOrNewMethodId, o2.frequencyOrNewMethodId);
            }
        });
        out.nextByte('A');
        compressMethods(out, evaluationContext.orderedMethods);
        out.nextByte('A');
    }

    private static String printTill(Output out, String tail, String till) {
        return printTill(out.asPrintableStream(), tail, till);
    }

    private static class EvaluationContext {
        final Index<Method> methods;
        final Method[] orderedMethods;
        final int[][] stackTraces;
        final byte[][] symbols;

        final SampleList.Result sampleList;

        final LzNodeTree nodeTree = new LzNodeTree();

        EvaluationContext(SampleList.Result sampleList, Index<Method> methods, int[][] stackTraces, byte[][] symbols) {
            this.sampleList = sampleList;
            this.methods = methods;
            this.stackTraces = stackTraces;
            this.symbols = symbols;

            orderedMethods = new Method[methods.size()];
            methods.orderedKeys(orderedMethods);
        }
    }

    private interface Output {
        void writeVar(long v) throws IOException;

        void write6(int v) throws IOException;

        void write18(int v) throws IOException;

        void write30(int v) throws IOException;

        void write(byte[] data) throws IOException;

        void nextByte(int ch) throws IOException;

        int pos();

        PrintStream asPrintableStream();
    }

    public static class HtmlOut implements Output {

        private final OutputStream out;

        private int pos = 0;

        public HtmlOut(OutputStream out) {
            this.out = out;
        }

        public void resetPos() {
            pos = 0;
        }

        public void nextByte(int ch) throws IOException {
            int c = ch;
            switch (ch) {
                case 0:
                    c = 127;
                    break;
                case '\r':
                    c = 126;
                    break;
                case '&':
                    c = 125;
                    break;
                case '<':
                    c = 124;
                    break;
                case '>':
                    c = 123;
                    break;
            }
            out.write(c);
            pos++;
        }

        @Override
        public void writeVar(long v) throws IOException {
            while (v >= 61) {
                int b = 61 + (int) (v % 61);
                nextByte(b);
                v /= 61;
            }
            nextByte((int) v);
        }

        @Override
        public void write6(int v) throws IOException {
            if ((v & (0xFFFFFFC0)) != 0) {
                throw new IllegalArgumentException("Value " + v + " is out of bounds");
            }
            nextByte(v);
        }

        @Override
        public void write18(int v) throws IOException {
            if ((v & (~0x3FFFF)) != 0) {
                throw new IllegalArgumentException("Value " + v + " is out of bounds");
            }
            for (int i = 0; i < 3; i++) {
                nextByte(v & 0x3F);
                v >>>= 6;
            }
        }

        @Override
        public void write30(int v) throws IOException {
            if ((v & 0xFFFF_FFFF_C000_0000L) != 0) {
                throw new IllegalArgumentException("Value " + v + " is out of bounds");
            }
            for (int i = 0; i < 5; i++) {
                nextByte(v & 0x3F);
                v >>>= 6;
            }
        }

        @Override
        public void write(byte[] data) throws IOException {
            out.write(data);
            pos += data.length;
        }

        @Override
        public int pos() {
            return pos;
        }

        @Override
        public PrintStream asPrintableStream() {
            return new PrintStream(out);
        }

    }


}
