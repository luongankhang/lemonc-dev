package site.ilemon.codegen.ast;

public class Label {
    private final int i;
    private static int count = 0;

    public Label()
    {
        i = count++;
    }

    /**
     * Resets the label counter.
     * Must be called before starting each new compilation task to prevent label number conflicts within the same JVM process.
     */
    public static void resetCounter() {
        count = 0;
    }

    @Override
    public String toString()
    {
        return "Label_" + i;
    }
}