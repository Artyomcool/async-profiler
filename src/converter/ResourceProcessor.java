import java.io.*;

public class ResourceProcessor {

    static String getResource(String name) {
        try (InputStream stream = ResourceProcessor.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("No resource found");
            }

            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            for (int length; (length = stream.read(buffer)) != -1; ) {
                result.write(buffer, 0, length);
            }
            return result.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("Can't load resource with name " + name);
        }
    }

    static String printTill(PrintStream out, String data, String till) {
        System.out.println("printTill " + till);
        int index = data.indexOf(till);
        out.print(data.substring(0, index));
        return data.substring(index + till.length());
    }

    static String skipTill(String data, String till) {
        System.out.println("skipTill " + till);
        return data.substring(data.indexOf(till) + till.length());
    }
}
