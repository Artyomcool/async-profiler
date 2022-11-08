import one.jfr.*;
import one.jfr.Dictionary;
import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SimpleHeatmap extends ResourceProcessor {

    private static final int UNKNOWN_ID = -1;
    private static final String UNKNOWN_METHOD_NAME = "<UnknownMethod>";
    private static final String UNKNOWN_CLASS_NAME = "<UnknownClass>";
    private static final StackTrace UNKNOWN_STACK = new StackTrace(new long[]{UNKNOWN_ID}, new byte[]{3}, new int[]{0});
    private static final MethodRef UNKNOWN_METHOD_REF = new MethodRef(UNKNOWN_ID, UNKNOWN_ID, UNKNOWN_ID);
    private static final ClassRef UNKNOWN_CLASS_REF = new ClassRef(UNKNOWN_ID);

    private final List<Event> samples = new ArrayList<>();

    private final String title;
    private final boolean alloc;

    private Dictionary<StackTrace> stacks;
    private Dictionary<MethodRef> methodRefs;
    private Dictionary<ClassRef> classRefs;
    private Dictionary<byte[]> symbols;

    private long startMs;
    private long startTicks;
    private long ticksPerSec;

    public SimpleHeatmap(String title, boolean alloc) {
        this.title = title;
        this.alloc = alloc;
    }

    public void addEvent(Event event) {
        if (alloc) {
            if (event instanceof AllocationSample) {
                samples.add(event);
            }
        } else {
            if (event instanceof ExecutionSample) {
                samples.add(event);
            }
        }
    }

    public void finish(
            Dictionary<StackTrace> stacks,
            Dictionary<MethodRef> methodRefs,
            Dictionary<ClassRef> classRefs,
            Dictionary<byte[]> symbols,
            long startMs,
            long startTicks,
            long ticksPerSec
    ) {
        this.stacks = stacks;
        this.methodRefs = methodRefs;
        this.classRefs = classRefs;
        this.symbols = symbols;
        this.startMs = startMs;
        this.startTicks = startTicks;
        this.ticksPerSec = ticksPerSec;
    }

    private long ms(long ticks) {
        return (ticks - startTicks) * 1000 / ticksPerSec;
    }

    private EvaluationContext evaluate(long blockDurationMs) {
        System.out.println("Evaluation started");
        Collections.sort(samples);

        final Index<String> symbols = new Index<>();
        symbols.preallocate(this.symbols.size());
        Method rootMethod = new Method(symbols.index("all"), symbols.index(""));

        final Index<Method> methodIndex = new Index<>();

        methodIndex.index(rootMethod);

        Dictionary<int[]> stackTraces = new Dictionary<>();

        long durationExecutions = samples.isEmpty() ? 0 : ms(samples.get(samples.size() - 1).time);
        SampleBlock[] blocks = new SampleBlock[(int) (durationExecutions / blockDurationMs) + 1];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new SampleBlock();
        }

        Index<int[]> stackTracesRemap = new Index<int[]>() {
            @Override
            protected boolean equals(int[] k1, int[] k2) {
                return Arrays.equals(k1, k2);
            }

            @Override
            protected int hashCode(int[] key) {
                return Arrays.hashCode(key) * 0x5bd1e995;
            }
        };

        long procent = 0;
        long pp = 0;

        for (Event execution : samples) {
            if (pp * 100 / samples.size() != procent) {
                procent = pp * 100 / samples.size();
                System.out.print("Processing samples: " + procent + "%     \r");
            }
            pp++;
            if (pp > 1) {
                //break;
            }

            long timeMs = ms(execution.time);
            SampleBlock block = blocks[(int) (timeMs / blockDurationMs)];

            int[] stackTrace = stackTraces.get((long)execution.extra() << 32 | execution.stackTraceId);

            if (stackTrace != null) {
                block.stacks.add(stackTracesRemap.index(stackTrace));
                continue;
            }

            StackTrace originalTrace = stacks.getOrDefault(execution.stackTraceId, UNKNOWN_STACK);
            stackTrace = new int[originalTrace.methods.length + ((execution instanceof AllocationSample || execution instanceof ContendedLock) ? 1 : 0)];

            for (int i = originalTrace.methods.length - 1; i >= 0; i--) {
                long methodId = originalTrace.methods[i];
                byte type = originalTrace.types[i];
                int location = originalTrace.locations[i];

                MethodRef methodRef = methodRefs.getOrDefault(methodId, UNKNOWN_METHOD_REF);
                ClassRef classRef = classRefs.getOrDefault(methodRef.cls, UNKNOWN_CLASS_REF);

                byte[] classNameBytes = this.symbols.get(classRef.name);
                byte[] methodNameBytes = this.symbols.get(methodRef.name);

                String classNameString = classNameBytes == null ? UNKNOWN_CLASS_NAME : convertClassName(classNameBytes);
                String methodNameString = methodNameBytes == null ? UNKNOWN_METHOD_NAME : new String(methodNameBytes);

                int className = symbols.index(classNameString);
                int methodName = symbols.index(methodNameString);

                int index = originalTrace.methods.length - 1 - i;
                Method method = new Method(className, methodName, location, type, index == 0);
                stackTrace[index] = methodIndex.index(method);
            }
            if (execution instanceof AllocationSample) {
                ClassRef classRef = classRefs.getOrDefault(execution.extra(), UNKNOWN_CLASS_REF);
                byte[] classNameBytes = this.symbols.get(classRef.name);
                String classNameString = classNameBytes == null ? UNKNOWN_CLASS_NAME : convertClassName(classNameBytes);
                int className = symbols.index(classNameString);
                int methodName = symbols.index("");
                byte type = ((AllocationSample)execution).tlabSize == 0 ? (byte) 3 : (byte) 2;
                stackTrace[originalTrace.methods.length] = methodIndex.index(new Method(className, methodName, 0, type, false));
            } else if (execution instanceof ContendedLock) {
                ClassRef classRef = classRefs.getOrDefault(execution.extra(), UNKNOWN_CLASS_REF);
                byte[] classNameBytes = this.symbols.get(classRef.name);
                String classNameString = classNameBytes == null ? UNKNOWN_CLASS_NAME : convertClassName(classNameBytes);
                int className = symbols.index(classNameString);
                int methodName = symbols.index("");
                byte type = 5;
                stackTrace[originalTrace.methods.length] = methodIndex.index(new Method(className, methodName, 0, type, false));
            }

            stackTraces.put((long)execution.extra() << 32 | execution.stackTraceId, stackTrace);
            block.stacks.add(stackTracesRemap.index(stackTrace));
        }
        System.out.print("Processing samples: done     \n");

        System.out.println("Unique: " + stackTracesRemap.size() + "/" + stackTraces.size());

        int[][] stacks = new int[stackTracesRemap.size()][];
        stackTracesRemap.orderedKeys(stacks);

        String[] symbolBytes = new String[symbols.size()];
        symbols.orderedKeys(symbolBytes);

        return new EvaluationContext(
                Arrays.asList(blocks),
                methodIndex,
                stacks,
                symbolBytes
        );
    }

    private String convertClassName(byte[] className) {
        if (className.length == 0) {
            return "";
        }
        int arrayDepth = 0;
        while (className[arrayDepth] == '[') {
            arrayDepth++;
        }

        StringBuilder sb = new StringBuilder(toJavaClassName(className, arrayDepth));
        while (arrayDepth-- > 0) {
            sb.append("[]");
        }
        return sb.toString();
    }

    private String toJavaClassName(byte[] symbol, int start) {
        switch (symbol[start]) {
            case 'B':
                return "byte";
            case 'C':
                return "char";
            case 'S':
                return "short";
            case 'I':
                return "int";
            case 'J':
                return "long";
            case 'Z':
                return "boolean";
            case 'F':
                return "float";
            case 'D':
                return "double";
            case 'L':
                return new String(symbol, start + 1, symbol.length - start - 2, StandardCharsets.UTF_8).replace('/', '.');
            default:
                return new String(symbol, start, symbol.length - start, StandardCharsets.UTF_8).replace('/', '.');
        }
    }

    private void compressMethods(Output out, Method[] methods) {
        for (Method method : methods) {
            out.write36((long) method.methodName << 18L | method.className);
            out.write36((long) method.location << 4L | method.type);
        }
    }

    public void dump(PrintStream stream) {
        Output out = new HtmlOut(stream);

        EvaluationContext evaluationContext = evaluate(20);

        String tail = getResource("/flame.html");

        tail = printTill(out, tail, "/*height:*/300");
        out.print(300);

        tail = printTill(out, tail, "/*if heatmap css:*/");
        tail = printTill(out, tail, "/*end if heatmap css*/");

        tail = printTill(out, tail, "/*if heatmap html:*/");

        tail = printTill(out, tail, "/*executionsHeatmap:*/");
        int was = out.pos();
        printHeatmap(out, evaluationContext);
        System.out.println("heatmap " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");

        tail = printTill(out, tail, "/*methods:*/");
        was = out.pos();
        printMethods(out, evaluationContext);
        System.out.println("methods " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");

        tail = printTill(out, tail, "/*end if heatmap html*/");
        tail = printTill(out, tail, "/*title:*/");
        out.print(title);

        tail = printTill(out, tail, "/*if flamegraph html:*/");
        tail = skipTill(tail, "/*end if flamegraph html*/");

        tail = printTill(out, tail, "/*reverse:*/false");
        out.print("true");

        tail = printTill(out, tail, "/*depth:*/0");
        out.print(0);

        tail = printTill(out, tail, "/*if flamegraph js:*/");
        tail = skipTill(tail, "/*end if flamegraph js*/");

        tail = printTill(out, tail, "/*if heatmap js:*/");
        tail = printTill(out, tail, "/*ticksPerSecond:*/1");
        out.print(ticksPerSec);

        tail = printTill(out, tail, "/*startMs:*/0");
        out.print(startMs);

        tail = printTill(out, tail, "/*cpool:*/");
        was = out.pos();
        printConstantPool(out, evaluationContext);
        System.out.println("constant pool " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");

        tail = printTill(out, tail, "/*end if heatmap js*/");

        tail = printTill(out, tail, "/*frames:*/");
        out.print(tail);

    }

    private void printHeatmap(Output out, EvaluationContext context) {
        Histogram histogram = new Histogram();

        int veryStart = out.pos();
        int synonymsCount = 61 * 61;

        int nextId = synonymsCount;
        LzNode root = new LzNode(nextId++);
        LzNode next = new LzNode(nextId++);
        List<LzNode> allNodes = new ArrayList<>();
        allNodes.add(root);

        int stacksCount = 0;
        for (SampleBlock block : context.blocks) {
            for (int i = 0; i < block.stacks.size; i++) {
                stacksCount++;
                LzNode current = root;
                int stackId = block.stacks.list[i];
                int[] stack = context.stackTraces[stackId - 1];

                for (int methodId : stack) {
                    LzNode prev = current;
                    current = current.putIfAbsent(methodId, next);
                    if (current == null) {
                        next.parent = prev;
                        allNodes.add(next);
                        current = root;
                        next = new LzNode(nextId++);

                        prev.count++;
                        context.orderedMethods[methodId - 1].tmp++;
                    }
                }
            }
        }

        Arrays.sort(context.orderedMethods, new Comparator<Method>() {
            @Override
            public int compare(Method o1, Method o2) {
                return Integer.compare(o2.tmp, o1.tmp);
            }
        });

        for (int i = 0; i < context.orderedMethods.length; i++) {
            Method method = context.orderedMethods[i];
            method.tmp = i + 1; // zero is reserved for no method
        }

        context.methods.orderedKeys(context.orderedMethods);

        Collections.sort(allNodes, new Comparator<LzNode>() {
            @Override
            public int compare(LzNode o1, LzNode o2) {
                return Integer.compare(o2.count, o1.count);
            }
        });

        for (LzNode node : allNodes) {
            histogram.add(node.count, "node count");
        }

        IndexInt starts = new IndexInt();
        starts.index(context.methods.size() + 1);

        for (SampleBlock block : context.blocks) {
            for (int i = 0; i < block.stacks.size; i++) {
                int stackId = block.stacks.list[i];
                int[] stack = context.stackTraces[stackId - 1];
                starts.index(context.orderedMethods[stack[0] - 1].tmp);
            }
        }

        int[] startsOut = new int[starts.size()];
        starts.orderedKeys(startsOut);

        for (int method : startsOut) {
            out.writeVar(method);
        }

        int was = out.pos();
        {
            int maxStackSize = 0;
            for (SampleBlock block : context.blocks) {
                maxStackSize = Math.max(block.stacks.size, maxStackSize);
            }

            long[] stackSizes = new long[maxStackSize + 1];
            for (SampleBlock block : context.blocks) {
                maxStackSize = Math.max(block.stacks.size, maxStackSize);
                stackSizes[block.stacks.size]++;
            }
            for (int i = 0; i < stackSizes.length; i++) {
                stackSizes[i] = (-stackSizes[i] << 32) | i;
            }
            Arrays.sort(stackSizes);

            out.writeVar(stackSizes.length);
            System.out.println("stackSizes.length "  + stackSizes.length);

            BitSet allBitSet = new BitSet();
            for (long stackSizeTerm : stackSizes) {
                int stackSize = (int) stackSizeTerm;
                out.writeVar(stackSize);

                BitSet bitSet = new BitSet();

                int j = 0;
                for (int i = 0; i < context.blocks.size(); i++) {
                    SampleBlock block = context.blocks.get(i);
                    if (allBitSet.get(i)) {
                        continue;
                    }
                    if (block.stacks.size == stackSize) {
                        bitSet.set(j);
                        allBitSet.set(i);
                    }
                    j++;
                }
                out.writeVar(bitSet.length());
                int b = 0;
                int c = 0;
                for (int i = 0; i < bitSet.length(); i++) {
                    b = (b << 1) | (bitSet.get(i) ? 1 : 0);
                    c++;
                    if (c == 6) {
                        out.write6(b);
                        c = 0;
                        b = 0;
                    }
                }
                if (c != 0) {
                    out.write6(b << (6 - c));
                }
            }
        }
        System.out.println("stack sizes " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");

        was = out.pos();
        IndexInt synonyms = new IndexInt();
        int synonymsDelta = 0;
        if (allNodes.size() < synonymsCount) {
            int oldSynonymsCount = synonymsCount;
            synonymsCount = allNodes.size();
            synonymsDelta = oldSynonymsCount - synonymsCount;
        }

        System.out.println("synonymsCount " + synonymsCount);
        System.out.println("before synonyms pos " + (out.pos() - veryStart));
        for (LzNode node : allNodes.subList(0, synonymsCount)) {
            synonyms.index(node.id - synonymsDelta);
            out.writeVar(node.id - synonymsDelta);
        }
        System.out.println("synonyms " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");
        System.out.println("after synonyms pos " + (out.pos() - veryStart));

        nextId = synonymsCount;
        root = new LzNode(nextId++);
        next = new LzNode(nextId++);
        allNodes.clear();
        allNodes.add(root);

        System.out.println("before bodies " + (out.pos() - veryStart));
        was = out.pos();
        List<LzNode>[] blocksData = new ArrayList[context.stackTraces.length];
        for (SampleBlock block : context.blocks) {
            for (int i = 0; i < block.stacks.size; i++) {
                LzNode current = root;
                int stackId = block.stacks.list[i];
                int[] stack = context.stackTraces[stackId - 1];

                List<LzNode> bestSample = blocksData[stackId - 1];
                if (bestSample == null) {
                    blocksData[stackId - 1] = bestSample = new ArrayList<>();
                } else {
                    bestSample.clear();
                }

                for (int methodId : stack) {
                    LzNode prev = current;
                    current = current.putIfAbsent(methodId, next);
                    if (current == null) {
                        current = root;
                        allNodes.add(next);
                        bestSample.add(next);
                        next.parent = prev;
                        next = new LzNode(nextId++);

                        int index = synonyms.index(prev.id, -1);
                        int data = index == -1 ? prev.id : index - 1;
                        out.writeVar(data);

                        int newMethodId = context.orderedMethods[methodId - 1].tmp;
                        out.writeVar(newMethodId);
                    }
                }
                if (current != root) {
                    bestSample.add(current);
                }
            }
        }
        System.out.println("bodies " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");
        System.out.println("after bodies pos " + (out.pos() - veryStart));

        for (SampleBlock block : context.blocks) {
            for (int i = 0; i < block.stacks.size; i++) {
                int stackId = block.stacks.list[i];
                for (LzNode lzNode : blocksData[stackId - 1]) {
                    lzNode.count++;
                }
                histogram.add(blocksData[stackId - 1].size(), "chunk counts per sample");
            }
        }

        int total = 0;
        for (LzNode allNode : allNodes) {
            histogram.add(allNode.count, "bodies2 node count");
            total += allNode.count;
        }

        Collections.sort(allNodes, new Comparator<LzNode>() {
            @Override
            public int compare(LzNode o1, LzNode o2) {
                return Integer.compare(o2.count, o1.count);
            }
        });

        synonyms = new IndexInt();
        for (LzNode node : allNodes.subList(0, synonymsCount)) {
            synonyms.index(node.id);
            out.writeVar(node.id);
        }

        Collection<LzNode> chunks = new LinkedHashSet<>();
        int chunksCount = 0;
        was = out.pos();
        for (SampleBlock block : context.blocks) {
            for (int i = 0; i < block.stacks.size; i++) {
                int stackId = block.stacks.list[i];
                for (LzNode lzNode : blocksData[stackId - 1]) {
                    int index = synonyms.index(lzNode.id, -1);
                    int data = index == -1 ? lzNode.id : index - 1;

                    int t = out.pos();
                    out.writeVar(data);
                    histogram.add(out.pos() - t, "tail size");
                    chunksCount++;

                    chunks.add(lzNode);
                }
            }
        }


        System.out.println("bodies2 " + (out.pos() - was) / 1024.0 / 1024.0 + " MB");

        System.out.println("chunks count " + chunks.size());

        for (LzNode node : allNodes) {
            node.count = 0;
        }
        for (LzNode node : chunks) {
            node.calcSize();
        }

        chunks = new ArrayList<>(chunks);
        Collections.sort((List)chunks, new Comparator<LzNode>() {
            @Override
            public int compare(LzNode o1, LzNode o2) {
                return Long.compare((long)o2.count << 32 | o2.id, (long)o1.count << 32 | o1.id);
            }
        });
        int storageSize = 0;
        for (LzNode node : chunks) {
            //System.out.println(node.id - synonyms.size() + " " + Math.abs(node.count) + " " + storageSize);
            if (node.count > 0) {
                storageSize += node.count;
                while (node.count > 0) {
                    node.count = -node.count;
                    node = node.parent;
                }
            }
        }
        System.out.println("storageSize " + storageSize * 4 / 1024.0 / 1024.0 + " MB (" + storageSize + ")");

        out.write36(chunks.size());
        out.write36(synonymsCount);
        out.write36(context.blocks.size());
        out.write36(startsOut.length);
        out.write36(storageSize);
        out.write36(chunksCount);
        out.write36(nextId - synonymsCount - 1);
        out.write36(stacksCount);
        System.out.println("stacksCount " + stacksCount);

        System.out.println("all data length " + (out.pos() - veryStart) / 1024.0 / 1024.0 + " MB (" + (out.pos() - veryStart) +")");
        histogram.print();
    }

    private void printConstantPool(Output out, EvaluationContext evaluationContext) {
        for (String symbol : evaluationContext.symbols) {
            out.write("\"");
            out.write(symbol);
            out.write("\",");
        }
    }

    private void printMethods(Output out, EvaluationContext evaluationContext) {
        System.out.println("methods count " + evaluationContext.orderedMethods.length);
        Arrays.sort(evaluationContext.orderedMethods, new Comparator<Method>() {
            @Override
            public int compare(Method o1, Method o2) {
                return Integer.compare(o1.tmp, o2.tmp);
            }
        });
        compressMethods(out, evaluationContext.orderedMethods);
    }

    private static String printTill(Output out, String tail, String till) {
        return printTill(out.asPrintableStream(), tail, till);
    }

    private static class Method {

        final int className;
        final int methodName;
        final int location;
        final byte type;
        final boolean start;

        public int tmp;

        Method(int className, int methodName) {
            this(className, methodName, 0, (byte) 3, true);
        }

        Method(int className, int methodName, int location, byte type, boolean start) {
            this.className = className;
            this.methodName = methodName;
            this.location = location;
            this.type = type;
            this.start = start;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Method method = (Method) o;

            if (className != method.className) return false;
            if (methodName != method.methodName) return false;
            if (location != method.location) return false;
            if (type != method.type) return false;
            return start == method.start;
        }

        @Override
        public int hashCode() {
            int result = className;
            result = 31 * result + methodName;
            result = 31 * result + location;
            result = 31 * result + (int) type;
            result = 31 * result + (start ? 1 : 0);
            return result;
        }
    }

    private static class LzNode extends Dictionary<LzNode> {
        String method;
        int id;
        int count;
        LzNode parent;

        private LzNode(int id) {
            this.id = id;
        }

        public void print() {
            if (parent != null) {
                parent.print();
            }
            System.out.println(method);
        }

        public int calcSize() {
            if (count == 0) {
                count = parent == null ? 0 : parent.calcSize() + 1;
            }
            return count;
        }
    }

    private static class SampleBlock {
        IntList stacks = new IntList();
    }

    private static class EvaluationContext {
        final Index<Method> methods;
        final Method[] orderedMethods;
        final int[][] stackTraces;
        final String[] symbols;

        List<SampleBlock> blocks;

        private EvaluationContext(List<SampleBlock> blocks, Index<Method> methods, int[][] stackTraces, String[] symbols) {
            this.blocks = blocks;
            this.methods = methods;
            this.stackTraces = stackTraces;
            this.symbols = symbols;

            orderedMethods = new Method[methods.size()];
            methods.orderedKeys(orderedMethods);
        }
    }

    private interface Output {
        void writeVar(long v);

        void write6(int v);

        void write36(long v);

        void write(String data);

        PrintStream asPrintableStream();

        void print(long x);
        void print(String x);

        int pos();
    }

    public static class HtmlOut implements Output {

        private final PrintStream out;

        private int pos = 0;

        public HtmlOut(PrintStream out) {
            this.out = out;
        }

        private void nextByte(int ch) {
            pos++;
            char c;
            switch (ch) {
                case 0:
                    c = (char)127;
                    break;
                case '\r':
                    c = (char)126;
                    break;
                case '&':
                    c = (char)125;
                    break;
                case '<':
                    c = (char)124;
                    break;
                case '>':
                    c = (char)123;
                    break;
                default:
                    c = (char)ch;
                    break;
            }
            out.append(c);
        }

        @Override
        public void writeVar(long v) {
            while (v >= 61) {
                int b = 61 + (int) (v % 61);
                nextByte(b);
                v /= 61;
            }
            nextByte((int) v);
        }

        @Override
        public void write6(int v) {
            if ((v & (0xFFFFFFC0)) != 0) {
                throw new IllegalArgumentException("Value " + v + " is out of bounds");
            }
            nextByte(v);
        }

        @Override
        public void write36(long v) {
            if ((v & 0xFFFFFFF000000000L) != 0) {
                throw new IllegalArgumentException("Value " + v + " is out of bounds");
            }
            for (int i = 0; i < 6; i++) {
                nextByte((int) (v & 0x3F));
                v >>>= 6;
            }
        }

        @Override
        public void write(String data) {
            out.append(data);
            pos += data.length();
        }

        @Override
        public void print(long x) {
            out.print(x);
            pos += 8;
        }

        @Override
        public void print(String x) {
            out.print(x);
            pos += x.length();
        }

        @Override
        public int pos() {
            return pos;
        }

        @Override
        public PrintStream asPrintableStream() {
            return out;
        }
    }


    private static class Histogram {
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
                    System.out.printf("%4d: %d (%.3f) [%.3f]\n", i - 1, c, c/(double)histogram[COUNT], nowCount/(double)histogram[COUNT]);
                }
                if (histogram[0] != 0) {
                    int c = histogram[0];
                    nowCount += c;
                    System.out.printf(">%3d: %d (%.3f) [%.3f]\n", COUNT - 1, c, c/(double)histogram[COUNT], nowCount/(double)histogram[COUNT]);
                }
                System.out.println();
            }
        }
    }

}
