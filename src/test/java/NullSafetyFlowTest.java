import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.backend.c.CBackend;
import site.ilemon.backend.c.NativeToolchain;
import site.ilemon.compiler.LemonC;
import site.ilemon.compiler.ModuleLoader;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.lexer.Lexer;
import site.ilemon.optimizer.AstOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * End-to-end regression and verification test suite for LemonC's null-safety,
 * flow analysis, condition narrowing, and memory-safety runtime traps.
 */
public class NullSafetyFlowTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testNarrowingInIfCondition() throws Exception {
        String src = """
                void main() {
                    int x = 42;
                    int* p = &x;
                    if (p != null) {
                        *p = 10;
                        printf("%d", *p);
                    }
                }
                """;
        IrModule module = lowerSource(src);
        String cCode = new CBackend().generate(module);
        // The dereferences inside the if branch must NOT contain redundant lemon_require_ptr
        assertFalse("Proven non-null pointer inside 'if (p != null)' should elide lemon_require_ptr",
                cCode.contains("lemon_require_ptr(p)"));

        String jvmOut = JvmTestSupport.compileAndRun("Main", src);
        String nativeOut = runNative(module);
        assertEquals("10", jvmOut);
        assertEquals("10", nativeOut);
    }

    @Test
    public void testNarrowingAfterEarlyReturn() throws Exception {
        String src = """
                int check(int* p) {
                    if (p == null) {
                        return -1;
                    }
                    *p = 20;
                    return *p;
                }
                void main() {
                    int x = 5;
                    int res = check(&x);
                    printf("%d", res);
                }
                """;
        IrModule module = lowerSource(src);
        String cCode = new CBackend().generate(module);
        // Inside check(), following the if (p == null) return;, p is proven non-null
        // so *p = 20 and return *p must not have lemon_require_ptr(p).
        int firstCheck = cCode.indexOf("int32_t check(");
        assertTrue(firstCheck >= 0);
        String checkFn = cCode.substring(firstCheck, cCode.indexOf("int32_t main("));
        assertFalse("Pointer dereference after early return must elide lemon_require_ptr",
                checkFn.contains("lemon_require_ptr(p)"));

        String jvmOut = JvmTestSupport.compileAndRun("Main", src);
        String nativeOut = runNative(module);
        assertEquals("20", jvmOut);
        assertEquals("20", nativeOut);
    }

    @Test
    public void testNestedConditionAndShortCircuit() throws Exception {
        String src = """
                void main() {
                    int* p = null;
                    if (p != null && *p == 42) {
                        printf("unreachable");
                    } else {
                        printf("safe");
                    }
                }
                """;
        IrModule module = lowerSource(src);
        String jvmOut = JvmTestSupport.compileAndRun("Main", src);
        String nativeOut = runNative(module);
        assertEquals("safe", jvmOut);
        assertEquals("safe", nativeOut);
    }

    @Test
    public void testLoopNonEnteringWhenNull() throws Exception {
        String src = """
                void main() {
                    int x = 100;
                    int* p = &x;
                    while (p != null) {
                        printf("%d", *p);
                        p = null;
                    }
                }
                """;
        IrModule module = lowerSource(src);
        String jvmOut = JvmTestSupport.compileAndRun("Main", src);
        String nativeOut = runNative(module);
        assertEquals("100", jvmOut);
        assertEquals("100", nativeOut);
    }

    @Test
    public void testStructPointerFieldNarrowing() throws Exception {
        String src = """
                struct Point {
                    int x;
                    int y;
                };
                void main() {
                    struct Point pt;
                    pt.x = 1;
                    pt.y = 2;
                    struct Point* p = &pt;
                    if (p != null) {
                        p->x = 10;
                        p->y = 20;
                        printf("%d %d", p->x, p->y);
                    }
                }
                """;
        IrModule module = lowerSource(src);
        String cCode = new CBackend().generate(module);
        assertFalse("Struct pointer access inside 'if (p != null)' must elide lemon_require_ptr",
                cCode.contains("lemon_require_ptr(p)"));

        String jvmOut = JvmTestSupport.compileAndRun("Main", src);
        String nativeOut = runNative(module);
        assertEquals("10 20", jvmOut);
        assertEquals("10 20", nativeOut);
    }

    @Test
    public void testArrayNullCheckingAndAssignment() throws Exception {
        String src = """
                void checkArr(int a[]) {
                    if (a == null) {
                        printf("null ");
                    } else {
                        printf("notnull ");
                    }
                }
                void main() {
                    int a[3];
                    a[0] = 7;
                    checkArr(a);
                    a = null;
                    checkArr(a);
                    if (a == null) {
                        printf("is_null");
                    }
                }
                """;
        IrModule module = lowerSource(src);
        String jvmOut = JvmTestSupport.compileAndRun("Main", src);
        String nativeOut = runNative(module);
        assertEquals("notnull null is_null", jvmOut);
        assertEquals("notnull null is_null", nativeOut);
    }

    @Test
    public void testRuntimeTrapOnNullDerefOnBothBackends() throws Exception {
        String src = """
                void main() {
                    int* p = null;
                    *p = 99;
                }
                """;
        IrModule module = lowerSource(src);

        // Native execution must abort and output the error message
        String nativeError = runNativeExpectingFailure(module);
        assertTrue("Native error output should contain null dereference diagnostic: " + nativeError,
                nativeError.contains("Lemon runtime error: null pointer dereference"));

        // JVM execution must fail with the exact same error message
        String jvmError = runJvmExpectingFailure("Main", src);
        assertTrue("JVM error output should contain null dereference diagnostic: " + jvmError,
                jvmError.contains("Lemon runtime error: null pointer dereference"));
    }

    @Test
    public void testRuntimeTrapOnStructPointerNullFieldAccessOnBothBackends() throws Exception {
        String src = """
                struct Node {
                    int val;
                };
                void main() {
                    struct Node* p = null;
                    p->val = 99;
                }
                """;
        IrModule module = lowerSource(src);

        String nativeError = runNativeExpectingFailure(module);
        assertTrue("Native error output should contain null dereference diagnostic: " + nativeError,
                nativeError.contains("Lemon runtime error: null pointer dereference"));

        String jvmError = runJvmExpectingFailure("Main", src);
        assertTrue("JVM error output should contain null dereference diagnostic: " + jvmError,
                jvmError.contains("Lemon runtime error: null pointer dereference"));
    }

    // =============================================================== helpers

    private IrModule lowerSource(String source) throws Exception {
        File dir = temporaryFolder.getRoot();
        File file = new File(dir, "Main.lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);

        Parser parser = new Parser(new Lexer(file));
        var program = parser.parse();
        new ModuleLoader().resolve(program, file.toPath());
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        assertTrue("semantic errors: " + semantic.getDiagnostics(), semantic.passOrNot());
        program = new AstOptimizer().optimize(program);
        return new AstToIrLowerer().lower(program);
    }

    private String runNative(IrModule module) throws Exception {
        Path runtimeRoot = Path.of("runtime").toAbsolutePath();
        Path sourceFile = Files.createTempFile("lemonc-null-native", ".c");
        new CBackend().generate(module, sourceFile);
        Path exe = Files.createTempFile("lemonc-null-native", ".exe");
        NativeToolchain toolchain = NativeToolchain.discover();
        try {
            Path executable = toolchain.compile(sourceFile, runtimeRoot.resolve("lemon_runtime.c"), exe);
            Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new AssertionError("native run failed: " + output);
            }
            return output.replace("\r\n", "\n").replace("\r", "\n");
        } finally {
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(exe);
        }
    }

    private String runNativeExpectingFailure(IrModule module) throws Exception {
        Path runtimeRoot = Path.of("runtime").toAbsolutePath();
        Path sourceFile = Files.createTempFile("lemonc-null-native-fail", ".c");
        new CBackend().generate(module, sourceFile);
        Path exe = Files.createTempFile("lemonc-null-native-fail", ".exe");
        NativeToolchain toolchain = NativeToolchain.discover();
        try {
            Path executable = toolchain.compile(sourceFile, runtimeRoot.resolve("lemon_runtime.c"), exe);
            Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            assertTrue("Expected non-zero exit code but got 0", code != 0);
            return output.replace("\r\n", "\n").replace("\r", "\n");
        } finally {
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(exe);
        }
    }

    private String runJvmExpectingFailure(String className, String source) throws Exception {
        JvmTestSupport.CompiledClass compiled = JvmTestSupport.compile(className, source);
        Process process = new ProcessBuilder("java",
                "-Dfile.encoding=UTF-8",
                "-cp", compiled.classDir().getPath(), className)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("JVM execution timed out");
        }
        assertTrue("Expected non-zero JVM exit code but got 0", process.exitValue() != 0);
        return output.replace("\r\n", "\n").replace("\r", "\n");
    }
}
