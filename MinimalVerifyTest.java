public class MinimalVerifyTest {
    public static class Buffer {
        public int id;
        public int[] data;

        public Buffer() {}
        public Buffer(Buffer src) {
            this.id = src.id;
            this.data = src.data;
        }
    }

    public static void main(String[] args) {
        Buffer b1 = new Buffer();
        b1.data = new int[2];
        b1.id = 1;
        b1.data[0] = 11;
        b1.data[1] = 22;

        Buffer b2 = new Buffer();
        b2.data = new int[2];
        b2 = new Buffer(b1);
        b2.id = 2;
        System.out.println("b1: " + b1.id + " [" + b1.data[0] + ", " + b1.data[1] + "]");
        System.out.println("b2: " + b2.id + " [" + b2.data[0] + ", " + b2.data[1] + "]");
    }
}
