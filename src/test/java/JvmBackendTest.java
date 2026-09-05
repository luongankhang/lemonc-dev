import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Structural tests for the direct-bytecode JVM backend.
 *
 * <p>Every test compiles Lemon source through the real pipeline
 * (Lexer → Parser → Semantic → Optimizer → LemonIR → {@code JvmBackend})
 * and then inspects the emitted {@code .class} bytes: method descriptors,
 * Code attribute limits, and raw opcode streams. Running programs through
 * the JVM additionally proves the emitted max_stack/max_locals and branch
 * layout satisfy the bytecode verifier.</p>
 */
public class JvmBackendTest {

    @Test
    public void emitsMethodWithExactLocalsAndDescriptorForIntegerParameters() throws Exception {
        byte[] bytes = JvmTestSupport.compileToBytes("AddFn", ""
                + "int add(int a, int b) { return a + b; }\n"
                + "void main() { printf(\"%d\", add(1, 2)); }\n");
        assertTrue(JvmTestSupport.hasMethod(bytes, "add", "(II)I"));
        JvmTestSupport.MethodInfo add = JvmTestSupport.method(JvmTestSupport.readMethods(bytes), "add");
        // Slots: a=0, b=1, result temp=2. (int params occupy one slot each.)
        assertEquals(3, add.maxLocals());
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "iadd"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "ireturn"));
    }

    @Test
    public void emitsVoidMethodWithPlainReturn() throws Exception {
        byte[] bytes = JvmTestSupport.compileToBytes("VoidFn", ""
                + "void noop() { }\n"
                + "void main() { noop(); }\n");
        assertTrue(JvmTestSupport.hasMethod(bytes, "noop", "()V"));
        // The Lemon entry point maps to the JVM main(String[])V method.
        assertTrue(JvmTestSupport.hasMethod(bytes, "main", "([Ljava/lang/String;)V"));
        assertTrue(JvmTestSupport.containsText(bytes, "noop"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "return"));
    }

    @Test
    public void wideValuesOccupyTwoLocalSlots() throws Exception {
        byte[] bytes = JvmTestSupport.compileToBytes("WideSlots", ""
                + "double mix(int a, double b) { return a + b; }\n"
                + "long shift(long a, int b) { return a + b; }\n"
                + "void main() { mix(1, 2.0); shift(1, 2); }\n");
        assertTrue(JvmTestSupport.hasMethod(bytes, "mix", "(ID)D"));
        JvmTestSupport.MethodInfo mix = JvmTestSupport.method(JvmTestSupport.readMethods(bytes), "mix");
        // Slots: a=0, b=1-2, conversion temp=3-4, add-result temp=5-6 (double temps are 2 slots).
        assertEquals(7, mix.maxLocals()); // int(1) + double(2) + temps(4)
        assertTrue(JvmTestSupport.hasMethod(bytes, "shift", "(JI)J"));
        JvmTestSupport.MethodInfo shift = JvmTestSupport.method(JvmTestSupport.readMethods(bytes), "shift");
        assertEquals(7, shift.maxLocals()); // long(2) + int(1) + temps(4)
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "dadd"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "ladd"));
    }

    @Test
    public void intWidensToLongAndDoubleConversionsAreEmitted() throws Exception {
        byte[] bytes = JvmTestSupport.compileToBytes("Conversions", ""
                + "void main() { long l; double d; int i; i = 3; l = i; d = i; }\n");
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "i2l"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "i2d"));
    }

    @Test
    public void ifElseJoinProducesBranchesAndVerifiableMerge() throws Exception {
        String source = ""
                + "int pick(int flag) {\n"
                + "    int result;\n"
                + "    if (flag > 0) { result = 1; } else { result = 2; }\n"
                + "    return result;\n"
                + "}\n"
                + "void main() { printf(\"%d\", pick(1)); printf(\"%d\", pick(-1)); }\n";
        byte[] bytes = JvmTestSupport.compileToBytes("IfElseJoin", source);
        // Both arms must jump around the other: a join merge that only verifies if
        // the stack heights agree. Running the class proves the branch layout is valid.
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "goto"));
        assertEquals("12", JvmTestSupport.compileAndRun("IfElseJoin", source));
    }

    @Test
    public void whileLoopBackEdgeIsVerifiable() throws Exception {
        String source = ""
                + "void main() {\n"
                + "    int sum; int i;\n"
                + "    sum = 0; i = 0;\n"
                + "    while (i < 5) { sum = sum + i; i = i + 1; }\n"
                + "    printf(\"%d\", sum);\n"
                + "}\n";
        byte[] bytes = JvmTestSupport.compileToBytes("Loop", source);
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "goto"));
        assertEquals("10", JvmTestSupport.compileAndRun("Loop", source));
    }

    @Test
    public void recursionUsesStaticMethodReference() throws Exception {
        String source = ""
                + "int factorial(int n) {\n"
                + "    int result;\n"
                + "    if (n <= 1) { result = 1; } else { result = n * factorial(n - 1); }\n"
                + "    return result;\n"
                + "}\n"
                + "void main() { printf(\"%d\", factorial(5)); }\n";
        byte[] bytes = JvmTestSupport.compileToBytes("Recursion", source);
        assertTrue(JvmTestSupport.hasMethod(bytes, "factorial", "(I)I"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "invokestatic"));
        assertEquals("120", JvmTestSupport.compileAndRun("Recursion", source));
    }

    @Test
    public void arrayAllocationLoadStoreAndLengthUseJvmArrayOpcodes() throws Exception {
        String source = ""
                + "void main() {\n"
                + "    int values[3]; int size;\n"
                + "    values[0] = 7; values[1] = 8; values[2] = 9;\n"
                + "    size = values.length;\n"
                + "    printf(\"%d\", values[1]);\n"
                + "    printf(\"%d\", size);\n"
                + "}\n";
        byte[] bytes = JvmTestSupport.compileToBytes("Arrays", source);
        assertTrue(JvmTestSupport.hasNewArray(bytes, "int"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "iaload"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "iastore"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "arraylength"));
        assertEquals("83", JvmTestSupport.compileAndRun("Arrays", source));
    }

    @Test
    public void boolArraysUseBooleanAtypeAndDescriptors() throws Exception {
        String source = ""
                + "void main() {\n"
                + "    bool flags[2];\n"
                + "    flags[0] = true; flags[1] = false;\n"
                + "    if (flags[0]) { printf(\"yes\"); }\n"
                + "}\n";
        byte[] bytes = JvmTestSupport.compileToBytes("BoolArrays", source);
        assertTrue(JvmTestSupport.hasNewArray(bytes, "boolean"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "baload"));
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "bastore"));
        assertEquals("yes", JvmTestSupport.compileAndRun("BoolArrays", source));
    }

    @Test
    public void stringLiteralsAndRuntimeHelpersAreLinkedThroughTheConstantPool() throws Exception {
        String source = "void main() { printf(\"%d\", 5); }\n";
        byte[] bytes = JvmTestSupport.compileToBytes("Strings", source);
        assertTrue(JvmTestSupport.containsText(bytes, "java/io/PrintStream"));
        assertTrue(JvmTestSupport.containsText(bytes, "java/lang/String"));
        assertEquals("5", JvmTestSupport.compileAndRun("Strings", source));
    }

    @Test
    public void codeIsWrittenDirectlyWithoutAnIntermediateAssemblyFile() throws Exception {
        JvmTestSupport.CompiledClass compiled = JvmTestSupport.compile("DirectBytes",
                "void main() { printf(\"ok\"); }\n");
        // The output directory (which also holds the snippet's .lemon source) must
        // contain exactly one .class and no Jasmin-style .j/.il assembly artifacts.
        String[] files = compiled.classDir().list();
        assertTrue(files != null && files.length >= 1);
        long classFiles = java.util.Arrays.stream(files).filter(f -> f.endsWith(".class")).count();
        assertEquals(1, classFiles);
        assertFalse(java.util.Arrays.asList(files).contains("DirectBytes.j"));
        assertFalse(java.util.Arrays.asList(files).contains("DirectBytes.il"));
        assertTrue(compiled.classBytes().length > 0);
        // Class magic present.
        byte[] bytes = compiled.classBytes();
        assertEquals(0xCA, bytes[0] & 0xFF);
        assertEquals(0xFE, bytes[1] & 0xFF);
        assertEquals(0xBA, bytes[2] & 0xFF);
        assertEquals(0xBE, bytes[3] & 0xFF);
        assertEquals("ok", JvmTestSupport.run(compiled));
    }

    @Test
    public void longDoubleConstAndPrintfDupHandlingVerifyAtRuntime() throws Exception {
        String source = ""
                + "void main() {\n"
                + "    long big; double d;\n"
                + "    big = 9223372036854775807;\n"
                + "    d = 1.5;\n"
                + "    printf(\"%d\", big);\n"
                + "    printf(\"%f\", d);\n"
                + "    printf(\"%f\", d);\n"
                + "}\n";
        byte[] bytes = JvmTestSupport.compileToBytes("WideConsts", source);
        assertTrue(JvmTestSupport.hasMnemonic(bytes, "ldc2_w"));
        assertEquals("92233720368547758071.51.5", JvmTestSupport.compileAndRun("WideConsts", source));
    }
}
