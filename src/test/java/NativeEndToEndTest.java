import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.compiler.LemonC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for the native C backend:
 * Compiles .lemon sources through LemonIR -> CBackend -> GCC -> Native executable,
 * runs the native binary, and verifies exact stdout output.
 */
public class NativeEndToEndTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String compileAndRunNative(String sourceCode, String baseName) throws Exception {
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path sourceFile = tempDir.resolve(baseName + ".lemon");
        Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outContent);
        PrintStream errStream = new PrintStream(errContent);

        int exitCode = LemonC.run(new String[]{sourceFile.toString(), "--target", "c", "--verbose"}, outStream, errStream);
        assertEquals("Native compile failed: " + errContent, 0, exitCode);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exePath = tempDir.resolve(baseName + (isWindows ? ".exe" : ""));
        assertTrue("Executable does not exist: " + exePath, Files.isRegularFile(exePath));

        Process process = new ProcessBuilder(exePath.toAbsolutePath().toString()).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int procExit = process.waitFor();
        assertEquals("Native process exited with non-zero status", 0, procExit);

        return stdout.trim();
    }

    @Test
    public void testNativeArithmeticAndFunctions() throws Exception {
        String code = """
                void main() {
                    int a;
                    int b;
                    a = 15;
                    b = 27;
                    printf("a=%d,b=%d,add=%d\\n", a, b, add(a, b));
                }
                int add(int x, int y) {
                    return x + y;
                }
                """;
        String output = compileAndRunNative(code, "ArithTest");
        assertEquals("a=15,b=27,add=42", output);
    }

    @Test
    public void testNativeLoopsAndConditionals() throws Exception {
        String code = """
                void main() {
                    int i;
                    int sum;
                    sum = 0;
                    for (i = 1; i <= 5; i = i + 1) {
                        sum = sum + i;
                    }
                    if (sum == 15) {
                        printf("sum=%d\\n", sum);
                    } else {
                        printf("wrong\\n");
                    }
                }
                """;
        String output = compileAndRunNative(code, "LoopTest");
        assertEquals("sum=15", output);
    }

    @Test
    public void testNativeArrayOperationsAndArc() throws Exception {
        String code = """
                void main() {
                    int arr[5];
                    int i;
                    for (i = 0; i < arr.length; i = i + 1) {
                        arr[i] = i * 10;
                    }
                    printf("len=%d,val2=%d,val4=%d\\n", arr.length, arr[2], arr[4]);
                }
                """;
        String output = compileAndRunNative(code, "ArrayArcTest");
        assertEquals("len=5,val2=20,val4=40", output);
    }

    @Test
    public void testNativeArrayParameterPassing() throws Exception {
        String code = """
                void setVal(int a[], int idx, int v) {
                    a[idx] = v;
                }
                void main() {
                    int arr[3];
                    arr[0] = 1;
                    arr[1] = 2;
                    arr[2] = 3;
                    setVal(arr, 1, 999);
                    printf("arr[1]=%d,len=%d\\n", arr[1], arr.length);
                }
                """;
        String output = compileAndRunNative(code, "ArrayParamTest");
        assertEquals("arr[1]=999,len=3", output);
    }

    @Test
    public void testNativeFloatAndDouble() throws Exception {
        String code = """
                void main() {
                    float f;
                    double d;
                    f = 3.5;
                    d = 7.25;
                    printf("f=%f,d=%f\\n", f, d);
                }
                """;
        String output = compileAndRunNative(code, "FloatDoubleTest");
        assertTrue(output.startsWith("f=3.5") && output.contains("d=7.25"));
    }

    @Test
    public void testEmitCOptionGeneratesSourceFile() throws Exception {
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path sourceFile = tempDir.resolve("EmitTest.lemon");
        Files.writeString(sourceFile, "void main() { printf(\"hello\\n\"); }", StandardCharsets.UTF_8);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{sourceFile.toString(), "--target", "c", "--emit-c"},
                new PrintStream(outContent), new PrintStream(errContent));
        assertEquals(0, exitCode);

        Path cFile = tempDir.resolve("EmitTest.c");
        assertTrue("Generated C file should exist", Files.isRegularFile(cFile));
        String cContent = Files.readString(cFile);
        assertTrue(cContent.contains("#include \"lemon_runtime.h\""));
        assertTrue(cContent.contains("int32_t main()"));
        assertTrue(cContent.contains("printf(\"hello\\n\");"));
    }

    @Test
    public void testNativeBreakAndContinue() throws Exception {
        String code = """
                void main() {
                    int i;
                    int sum;
                    sum = 0;
                    for (i = 0; i < 10; i = i + 1) {
                        if (i == 2) {
                            continue;
                        }
                        if (i == 6) {
                            break;
                        }
                        sum = sum + i;
                    }
                    printf("sum=%d\\n", sum);
                }
                """;
        String output = compileAndRunNative(code, "BreakContinueTest");
        assertEquals("sum=13", output);
    }

    @Test
    public void testNativeArcMultiFunctionPassing() throws Exception {
        String code = """
                void incArray(int arr[]) {
                    int i;
                    for (i = 0; i < arr.length; i = i + 1) {
                        arr[i] = arr[i] + 1;
                    }
                }
                void doubleArray(int arr[]) {
                    int i;
                    for (i = 0; i < arr.length; i = i + 1) {
                        arr[i] = arr[i] * 2;
                    }
                }
                void main() {
                    int a[3];
                    a[0] = 5;
                    a[1] = 10;
                    a[2] = 15;
                    incArray(a);
                    doubleArray(a);
                    printf("a0=%d,a1=%d,a2=%d\\n", a[0], a[1], a[2]);
                }
                """;
        String output = compileAndRunNative(code, "ArcMultiFuncTest");
        assertEquals("a0=12,a1=22,a2=32", output);
    }

    @Test
    public void testNativeBubbleSortExample() throws Exception {
        String code = Files.readString(Path.of("examples", "BubbleSort.lemon"), StandardCharsets.UTF_8);
        String output = compileAndRunNative(code, "BubbleSortNative");
        assertTrue(output.contains("11 12 22 25 34 64"));
    }

    @Test
    public void testNativeRuntimeDetectsSizeOverflow() throws Exception {
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path runtimeRoot = Path.of("runtime").toAbsolutePath();
        Path sourceFile = tempDir.resolve("OverflowCheck.c");
        Files.writeString(sourceFile, "#include \"lemon_runtime.h\"\n\nint main(void) {\n    lemon_array *a = lemon_array_new((size_t)-1, sizeof(int32_t), NULL);\n    (void)a;\n    return 0;\n}\n", StandardCharsets.UTF_8);

        Path exePath = tempDir.resolve("OverflowCheck");
        site.ilemon.backend.c.NativeToolchain toolchain = site.ilemon.backend.c.NativeToolchain.discover();
        toolchain.compile(sourceFile, runtimeRoot.resolve("lemon_runtime.c"), exePath);

        Process process = new ProcessBuilder(exePath.toAbsolutePath().toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertTrue("Expected overflow panic but got: " + output, exitCode != 0);
        assertTrue("Expected size overflow diagnostic but got: " + output, output.contains("size overflow"));
    }

    @Test
    public void testNativeFibonacciExample() throws Exception {
        String code = Files.readString(Path.of("examples", "Fib.lemon"), StandardCharsets.UTF_8);
        String output = compileAndRunNative(code, "FibNative");
        assertTrue(output.contains("144"));
    }
}
