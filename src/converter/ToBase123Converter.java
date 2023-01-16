import java.math.BigInteger;

public class ToBase123Converter {

    // log2(123^17) = 118.022746591 > 118 bits
    // log2(123^8) = 55.5401160427  > 55 bits -> one long

    private final BigInteger bi123 = BigInteger.valueOf(123);
    private final BigInteger bi123p9 = bi123.pow(9);

    public int[] buffer = new int[17];  // contains values 0..122

    // NOTE: this method ALLOCATES!!! So, do not use it extensively
    public void append(long highest55bits, long lowest62bits) {
        BigInteger highest = BigInteger.valueOf(highest55bits);
        BigInteger lowest = BigInteger.valueOf(lowest62bits);

        BigInteger result = highest.multiply(bi123p9).add(lowest);
        for (int i = 0; i < 17; i++) {
            buffer[i] = result.mod(bi123).intValue();
            result = result.divide(bi123);
        }
    }

}
