package one.heatmap;

public class Method {

    public final int className;
    public final int methodName;
    public final int location;
    public final byte type;
    public final boolean start;

    final long originalMethodId;

    Method next;

    public int frequencyOrNewMethodId;
    public int index;

    Method(int className, int methodName) {
        this(0, className, methodName, 0, (byte) 3, true);
    }

    Method(long originalMethodId, int className, int methodName, int location, byte type, boolean start) {
        this.originalMethodId = originalMethodId;
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