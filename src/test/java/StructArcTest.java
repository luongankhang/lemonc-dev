import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end correctness and performance test suite for Struct + ARC in LemonC.
 * Verifies parity between JVM and C backends, move semantics, pointer aliasing,
 * const immutability, nested structs, and ARC simulation/verification.
 */
public class StructArcTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int compile(Path source, String target, String... extraFlags) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        List<String> argsList = new ArrayList<>();
        argsList.add(source.toString());
        argsList.add("--target");
        argsList.add(target);
        argsList.addAll(Arrays.asList(extraFlags));
        int code = LemonC.run(argsList.toArray(new String[0]),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        if (code != 0) {
            System.err.println(target + " compile error: " + errors.toString(StandardCharsets.UTF_8));
        }
        return code;
    }

    private String runJvm(String className) throws Exception {
        return JvmTestSupport.run(className, new File(JvmBackend.DEFAULT_OUTPUT_DIR));
    }

    private String runNative(Path main) throws Exception {
        String name = main.getFileName().toString();
        String baseName = name.substring(0, name.length() - ".lemon".length());
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exe = main.getParent().resolve(baseName + (isWindows ? ".exe" : ""));
        assertTrue("Executable does not exist: " + exe, Files.isRegularFile(exe));

        Process process = new ProcessBuilder(exe.toAbsolutePath().toString()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("native process exited with non-zero status", 0, process.waitFor());
        return output.replace("\r\n", "\n").replace("\r", "\n");
    }

    private void assertOutputsMatch(Path source, String mainClass, String expectedOutput, String... extraFlags) throws Exception {
        assertEquals("JVM compile failed", 0, compile(source, "jvm", extraFlags));
        String jvmOut = runJvm(mainClass);

        assertEquals("C compile failed", 0, compile(source, "c", extraFlags));
        String nativeOut = runNative(source);

        assertEquals("JVM output does not match expected", expectedOutput, jvmOut);
        assertEquals("Native output does not match expected", expectedOutput, nativeOut);
        assertEquals("JVM and Native outputs must match", jvmOut, nativeOut);
    }

    private Path write(String name, String content) throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void structWithArrayField_JvmAndCParity() throws Exception {
        String code = """
                struct Buffer {
                    int id;
                    int data[3];
                };

                void main() {
                    struct Buffer b;
                    b.id = 100;
                    b.data[0] = 10;
                    b.data[1] = 20;
                    b.data[2] = 30;
                    printf("%d: %d %d %d\\n", b.id, b.data[0], b.data[1], b.data[2]);
                }
                """;
        Path file = write("StructArrayDemo.lemon", code);
        assertOutputsMatch(file, "StructArrayDemo", "100: 10 20 30\n", "--arc", "--arc-verify");
    }

    @Test
    public void structCopyAndAssignment_ARC() throws Exception {
        String code = """
                struct Buffer {
                    int id;
                    int data[2];
                };

                void main() {
                    struct Buffer b1;
                    b1.id = 1;
                    b1.data[0] = 11;
                    b1.data[1] = 22;

                    struct Buffer b2;
                    b2 = b1;
                    b2.id = 2;
                    printf("b1: %d [%d, %d]\\n", b1.id, b1.data[0], b1.data[1]);
                    printf("b2: %d [%d, %d]\\n", b2.id, b2.data[0], b2.data[1]);
                }
                """;
        Path file = write("StructCopyDemo.lemon", code);
        assertOutputsMatch(file, "StructCopyDemo", "b1: 1 [11, 22]\nb2: 2 [11, 22]\n", "--arc", "--arc-verify");
    }

    @Test
    public void structReturnAndPassing_MoveSemantics() throws Exception {
        String code = """
                struct Vector {
                    int x;
                    int y;
                    int scores[2];
                };

                struct Vector makeVector(int x, int y, int s0, int s1) {
                    struct Vector v;
                    v.x = x;
                    v.y = y;
                    v.scores[0] = s0;
                    v.scores[1] = s1;
                    return v;
                }

                void printVector(struct Vector v) {
                    printf("v(%d,%d)=[%d,%d]\\n", v.x, v.y, v.scores[0], v.scores[1]);
                }

                void main() {
                    struct Vector vec;
                    vec = makeVector(3, 4, 99, 100);
                    printVector(vec);
                }
                """;
        Path file = write("StructMoveDemo.lemon", code);
        assertOutputsMatch(file, "StructMoveDemo", "v(3,4)=[99,100]\n", "--arc", "--arc-verify");
    }

    @Test
    public void constStructWithInitializerList_ParityAndImmutability() throws Exception {
        String code = """
                struct Config {
                    int maxConnections;
                    int timeout;
                };

                const struct Config DEFAULT_CFG = { 100, 30 };

                void main() {
                    printf("cfg: %d %d\\n", DEFAULT_CFG.maxConnections, DEFAULT_CFG.timeout);
                }
                """;
        Path file = write("ConstStructDemo.lemon", code);
        assertOutputsMatch(file, "ConstStructDemo", "cfg: 100 30\n");

        // Verify that mutating a const struct is rejected
        String invalidCode = """
                struct Config {
                    int maxConnections;
                    int timeout;
                };

                const struct Config DEFAULT_CFG = { 100, 30 };

                void main() {
                    DEFAULT_CFG.maxConnections = 200;
                }
                """;
        Path invalidFile = write("InvalidConstMutate.lemon", invalidCode);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int codeResult = LemonC.run(new String[]{invalidFile.toString(), "--target", "jvm"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        assertEquals(1, codeResult);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("constants are immutable"));
    }

    @Test
    public void nestedStructWithArrays_Parity() throws Exception {
        String code = """
                struct Inner {
                    int values[2];
                };

                struct Outer {
                    int tag;
                    struct Inner inner;
                };

                void main() {
                    struct Outer o;
                    o.tag = 77;
                    o.inner.values[0] = 55;
                    o.inner.values[1] = 66;
                    printf("outer: %d, inner: [%d, %d]\\n", o.tag, o.inner.values[0], o.inner.values[1]);
                }
                """;
        Path file = write("NestedStructDemo.lemon", code);
        assertOutputsMatch(file, "NestedStructDemo", "outer: 77, inner: [55, 66]\n", "--arc", "--arc-verify");
    }

    @Test
    public void structPointerAliasing_Parity() throws Exception {
        String code = """
                struct Container {
                    int id;
                    int items[2];
                };

                void updateContainer(struct Container* c) {
                    c->id = 999;
                    c->items[0] = 123;
                }

                void main() {
                    struct Container c;
                    c.id = 1;
                    c.items[0] = 10;
                    c.items[1] = 20;

                    struct Container* ptr;
                    ptr = &c;
                    printf("before: %d [%d]\\n", c.id, c.items[0]);
                    updateContainer(ptr);
                    printf("after: %d [%d]\\n", c.id, c.items[0]);
                }
                """;
        Path file = write("PointerAliasDemo.lemon", code);
        assertOutputsMatch(file, "PointerAliasDemo", "before: 1 [10]\nafter: 999 [123]\n", "--arc", "--arc-verify");
    }

    @Test
    public void structWithStringAndPointer_Parity() throws Exception {
        String code = """
                struct Node {
                    int val;
                    string label;
                };

                void main() {
                    struct Node n;
                    n.val = 42;
                    n.label = "hello_struct";
                    printf("%d: %s\\n", n.val, n.label);
                }
                """;
        Path file = write("StructStringDemo.lemon", code);
        assertOutputsMatch(file, "StructStringDemo", "42: hello_struct\n");
    }

    @Test
    public void arcAnalysisVerification() throws Exception {
        String code = """
                struct Holder {
                    int arr[2];
                };

                void main() {
                    struct Holder h;
                    h.arr[0] = 5;
                    printf("%d\\n", h.arr[0]);
                }
                """;
        Path file = write("ArcAnalysisDemo.lemon", code);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = LemonC.run(new String[]{file.toString(), "--arc-analysis"}, new PrintStream(out), new PrintStream(err));
        if (exitCode != 0) {
            System.err.println("Arc analysis error: " + err.toString(StandardCharsets.UTF_8));
        }
        assertEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("ALLOC h.arr:@array"));
        assertTrue(output.contains("BOUNDS_CHECK h.arr"));
        assertTrue(output.contains("RELEASE h.arr"));
    }

    @Test
    public void zeroIterationLoopWithStruct_Parity() throws Exception {
        String code = """
                struct Vector {
                    int data[4];
                };

                void main() {
                    int i = 0;
                    for (i = 0; i < 0; i++) {
                        struct Vector v;
                    }
                    printf("zero loop ok\\n");
                }
                """;
        Path file = write("ZeroLoopTest.lemon", code);
        assertOutputsMatch(file, "ZeroLoopTest", "zero loop ok\n");
    }

    @Test
    public void loopIterationScopingAndNoLeak_Parity() throws Exception {
        String code = """
                struct Vector {
                    int id;
                    int data[2];
                };

                void main() {
                    int sum = 0;
                    int i = 0;
                    for (i = 0; i < 50; i++) {
                        struct Vector v;
                        v.id = i;
                        v.data[0] = i;
                        v.data[1] = i * 2;
                        sum += v.data[0] + v.data[1];
                    }
                    printf("sum=%d\\n", sum);
                }
                """;
        Path file = write("LoopScopingDemo.lemon", code);
        assertOutputsMatch(file, "LoopScopingDemo", "sum=3675\n");
    }

    @Test
    public void structBreakAndContinueScopeReleases_Parity() throws Exception {
        String code = """
                struct Vector {
                    int data[2];
                };

                void main() {
                    int count = 0;
                    int i = 0;
                    for (i = 0; i < 10; i++) {
                        struct Vector v;
                        v.data[0] = i;
                        if (i % 2 == 0) {
                            continue;
                        }
                        if (i > 7) {
                            break;
                        }
                        count += v.data[0];
                    }
                    printf("count=%d\\n", count);
                }
                """;
        Path file = write("BreakContinueDemo.lemon", code);
        assertOutputsMatch(file, "BreakContinueDemo", "count=16\n");
    }

    @Test
    public void earlyReturnWithInnerScopesAndBorrowedParams_Parity() throws Exception {
        String code = """
                struct Box {
                    int val[2];
                };

                int inspectBox(struct Box b, int flag) {
                    if (flag > 0) {
                        struct Box inner;
                        inner.val[0] = b.val[0] * 10;
                        return inner.val[0];
                    }
                    return b.val[1];
                }

                void main() {
                    struct Box b;
                    b.val[0] = 7;
                    b.val[1] = 99;
                    int r1 = inspectBox(b, 1);
                    int r2 = inspectBox(b, 0);
                    printf("r1=%d, r2=%d\\n", r1, r2);
                }
                """;
        Path file = write("EarlyReturnDemo.lemon", code);
        assertOutputsMatch(file, "EarlyReturnDemo", "r1=70, r2=99\n");
    }

    @Test
    public void structSelfAssignmentOptimization_Parity() throws Exception {
        String code = """
                struct Vector {
                    int data[3];
                };

                void main() {
                    struct Vector v;
                    v.data[0] = 11;
                    v.data[1] = 22;
                    v.data[2] = 33;
                    v = v;
                    printf("%d %d %d\\n", v.data[0], v.data[1], v.data[2]);
                }
                """;
        Path file = write("SelfAssignDemo.lemon", code);
        assertOutputsMatch(file, "SelfAssignDemo", "11 22 33\n");
    }

    @Test
    public void discardedStructReturnReleased_Parity() throws Exception {
        String code = """
                struct Vector {
                    int data[2];
                };

                struct Vector makeVec(int x, int y) {
                    struct Vector v;
                    v.data[0] = x;
                    v.data[1] = y;
                    return v;
                }

                void main() {
                    makeVec(10, 20);
                    struct Vector v = makeVec(30, 40);
                    printf("ok: %d %d\\n", v.data[0], v.data[1]);
                }
                """;
        Path file = write("DiscardedReturnDemo.lemon", code);
        assertOutputsMatch(file, "DiscardedReturnDemo", "ok: 30 40\n");
    }
}
