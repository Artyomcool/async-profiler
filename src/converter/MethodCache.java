import one.jfr.*;

import java.util.Arrays;

public class MethodCache {

    private static final int UNKNOWN_ID = -1;
    private static final MethodRef UNKNOWN_METHOD_REF = new MethodRef(UNKNOWN_ID, UNKNOWN_ID, UNKNOWN_ID);
    private static final ClassRef UNKNOWN_CLASS_REF = new ClassRef(UNKNOWN_ID);

    private Dictionary<MethodRef> methodRefs;
    private Dictionary<ClassRef> classRefs;
    private Dictionary<byte[]> symbols;

    private final SymbolTable symbolTable = new SymbolTable();
    private final int emptyIndex = symbolTable.index(new byte[0]);
    private final Index<Method> methodIndex = new Index<>();
    {
        methodIndex.index(new Method(symbolTable.index("all".getBytes()), emptyIndex));
    }

    private final Method[] nearCache = new Method[256 * 256];
    // It should be better to create dictionary with linked methods instead of open addressed hash table
    // but in most cases all methods should fit nearCache, so less code is better
    private final Dictionary<Method> farMethods = new Dictionary<>(1024 * 1024);

    public void assignConstantPool(
            Dictionary<MethodRef> methodRefs,
            Dictionary<ClassRef> classRefs,
            Dictionary<byte[]> symbols
    ) {
        this.methodRefs = methodRefs;
        this.classRefs = classRefs;
        this.symbols = symbols;
    }

    public void clear() {
        Arrays.fill(nearCache, null);
        farMethods.clear();
    }

    public int index(long methodId, int location, byte type, boolean firstInStack) {
        Method method;
        if (methodId < nearCache.length) {
            int mid = (int) methodId;
            method = nearCache[mid];
            if (method == null) {
                method = createMethod(methodId, location, type, firstInStack);
                nearCache[mid] = method;
                return method.index = methodIndex.index(method);
            }
        } else {
            // this should be extremely rare case
            method = farMethods.get(methodId);
            if (method == null) {
                method = createMethod(methodId, location, type, firstInStack);
                farMethods.put(methodId, method);
                return method.index = methodIndex.index(method);
            }
        }

        Method last = null;
        Method prototype = null;
        while (method != null) {
            if (method.originalMethodId == methodId) {
                if (method.location == location && method.type == type && method.start == firstInStack) {
                    return method.index;
                }
                prototype = method;
            }
            last = method;
            method = method.next;
        }

        if (prototype != null) {
            last.next = method = new Method(methodId, prototype.className, prototype.methodName, location, type, firstInStack);
            return method.index = methodIndex.index(method);
        }

        last.next = method = createMethod(methodId, location, type, firstInStack);

        return method.index = methodIndex.index(method);
    }

    public int indexForClass(int extra, byte type) {
        long methodId = (long) extra << 32 | 1L << 63;
        Method method = farMethods.get(methodId);
        Method last = null;
        if (method != null) {
            while (method != null) {
                if (method.originalMethodId == methodId) {
                    if (method.location == -1 && method.type == type && !method.start) {
                        return method.index;
                    }
                }
                last = method;
                method = method.next;
            }
        }

        ClassRef classRef = classRefs.getOrDefault(extra, UNKNOWN_CLASS_REF);
        byte[] classNameBytes = this.symbols.get(classRef.name);
        method = new Method(methodId, symbolTable.indexWithPostTransform(classNameBytes), emptyIndex, -1, type, false);
        if (last == null) {
            farMethods.put(methodId, method);
        } else {
            last.next = method;
        }
        return method.index = methodIndex.index(method);
    }

    private Method createMethod(long methodId, int location, byte type, boolean firstInStack) {
        MethodRef methodRef = methodRefs.getOrDefault(methodId, UNKNOWN_METHOD_REF);
        ClassRef classRef = classRefs.getOrDefault(methodRef.cls, UNKNOWN_CLASS_REF);

        byte[] classNameBytes = this.symbols.get(classRef.name);
        byte[] methodNameBytes = this.symbols.get(methodRef.name);

        int className = isNativeFrame(type)
                ? symbolTable.index(classNameBytes)
                : symbolTable.indexWithPostTransform(classNameBytes);
        int methodName = symbolTable.index(methodNameBytes);

        return new Method(methodId, className, methodName, location, type, firstInStack);
    }

    private boolean isNativeFrame(byte methodType) {
        return methodType >= FlameGraph.FRAME_NATIVE && methodType <= FlameGraph.FRAME_KERNEL;
    }

    public byte[][] orderedSymbolTable() {
        return symbolTable.orderedKeys();
    }

    public Index<Method> methodsIndex() {
        return methodIndex;
    }
}