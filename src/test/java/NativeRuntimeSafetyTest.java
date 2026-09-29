import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.backend.c.NativeToolchain;
import site.ilemon.compiler.LemonC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Validates native C runtime memory safety enhancements:
 * - Double release / use-after-free detection
 * - Invalid pointer release detection
 * - Heap buffer overflow canary detection
 * - Safe retain/release of NULL
 * - Array bounds check reporting
 * - Null pointer struct field access validation
 * - Allocation leak detection
 */
public class NativeRuntimeSafetyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private record RunResult(int exitCode, String output) {}

    private RunResult compileAndRunC(String cCode, String name) throws Exception {
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path runtimeRoot = Path.of("runtime").toAbsolutePath();
        Path sourceFile = tempDir.resolve(name + ".c");
        Files.writeString(sourceFile, cCode, StandardCharsets.UTF_8);

        Path exePath = tempDir.resolve(name);
        NativeToolchain toolchain = NativeToolchain.discover();
        toolchain.compile(sourceFile, runtimeRoot.resolve("lemon_runtime.c"), exePath);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path fullExe = isWindows ? exePath.resolveSibling(name + ".exe") : exePath;

        Process process = new ProcessBuilder(fullExe.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new RunResult(exitCode, output);
    }

    private RunResult compileAndRunLemon(String lemonCode, String name) throws Exception {
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path sourceFile = tempDir.resolve(name + ".lemon");
        Files.writeString(sourceFile, lemonCode, StandardCharsets.UTF_8);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{sourceFile.toString(), "--target", "c"},
                new PrintStream(outContent), new PrintStream(errContent));
        assertEquals("LemonC compile failed: " + errContent, 0, exitCode);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exePath = tempDir.resolve(name + (isWindows ? ".exe" : ""));
        assertTrue("Executable does not exist: " + exePath, Files.isRegularFile(exePath));

        Process process = new ProcessBuilder(exePath.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int procExit = process.waitFor();
        return new RunResult(procExit, output);
    }

    @Test
    public void testSafeNullRetainAndRelease() throws Exception {
        String code = """
                #include "lemon_runtime.h"
                int main(void) {
                    lemon_retain(NULL);
                    lemon_release(NULL);
                    return 0;
                }
                """;
        RunResult res = compileAndRunC(code, "SafeNullTest");
        assertEquals(0, res.exitCode());
    }

    @Test
    public void testDoubleReleaseDetected() throws Exception {
        String code = """
                #include "lemon_runtime.h"
                int main(void) {
                    lemon_array *arr = lemon_array_new(3, sizeof(int), NULL);
                    lemon_release(arr);
                    lemon_release(arr); // Double release
                    return 0;
                }
                """;
        RunResult res = compileAndRunC(code, "DoubleReleaseTest");
        assertTrue("Expected failure exit code", res.exitCode() != 0);
        assertTrue("Expected use-after-free or double release message, got: " + res.output(),
                res.output().contains("use-after-free") || res.output().contains("double release"));
    }

    @Test
    public void testUseAfterFreeRetainDetected() throws Exception {
        String code = """
                #include "lemon_runtime.h"
                int main(void) {
                    lemon_array *arr = lemon_array_new(3, sizeof(int), NULL);
                    lemon_release(arr);
                    lemon_retain(arr); // Retain after destroy
                    return 0;
                }
                """;
        RunResult res = compileAndRunC(code, "UseAfterFreeRetainTest");
        assertTrue("Expected failure exit code", res.exitCode() != 0);
        assertTrue("Expected use-after-free message, got: " + res.output(),
                res.output().contains("use-after-free"));
    }

    @Test
    public void testUseAfterFreeArrayAccessDetected() throws Exception {
        String code = """
                #include "lemon_runtime.h"
                int main(void) {
                    lemon_array *arr = lemon_array_new(3, sizeof(int), NULL);
                    lemon_release(arr);
                    lemon_array_at(arr, 0); // Index after free
                    return 0;
                }
                """;
        RunResult res = compileAndRunC(code, "UseAfterFreeAccessTest");
        assertTrue("Expected failure exit code", res.exitCode() != 0);
        assertTrue("Expected use-after-free or invalid array diagnostic, got: " + res.output(),
                res.output().contains("use-after-free") || res.output().contains("invalid array"));
    }

    @Test
    public void testInvalidPointerReleaseDetected() throws Exception {
        String code = """
                #include "lemon_runtime.h"
                int main(void) {
                    int stack_var = 42;
                    lemon_release(&stack_var);
                    return 0;
                }
                """;
        RunResult res = compileAndRunC(code, "InvalidReleaseTest");
        assertTrue("Expected failure exit code", res.exitCode() != 0);
        assertTrue("Expected invalid object diagnostic, got: " + res.output(),
                res.output().contains("invalid object") || res.output().contains("bad magic"));
    }

    @Test
    public void testHeapBufferOverflowCanaryDetected() throws Exception {
        String code = """
                #include "lemon_runtime.h"
                int main(void) {
                    char *buf = (char *)lemon_alloc(16);
                    // Write past allocated bounds, corrupting the trailer canary
                    buf[16] = 'X';
                    buf[17] = 'Y';
                    lemon_free(buf);
                    return 0;
                }
                """;
        RunResult res = compileAndRunC(code, "HeapOverflowTest");
        assertTrue("Expected failure exit code", res.exitCode() != 0);
        assertTrue("Expected heap buffer overflow diagnostic, got: " + res.output(),
                res.output().contains("heap buffer overflow detected"));
    }

    @Test
    public void testArrayBoundsCheckFormatting() throws Exception {
        String code = """
                #include "lemon_runtime.h"
                int main(void) {
                    lemon_array *arr = lemon_array_new(3, sizeof(int), NULL);
                    lemon_array_at(arr, 5); // Out of bounds
                    return 0;
                }
                """;
        RunResult res = compileAndRunC(code, "BoundsFormattingTest");
        assertTrue("Expected failure exit code", res.exitCode() != 0);
        assertTrue("Expected index 5 length 3 diagnostic, got: " + res.output(),
                res.output().contains("array index out of bounds") && res.output().contains("index 5, length 3"));
    }

    @Test
    public void testZeroLeaksOnValidCode() throws Exception {
        String code = """
                #include "lemon_runtime.h"
                int main(void) {
                    lemon_array *arr = lemon_array_new(10, sizeof(int), NULL);
                    lemon_string *str = lemon_string_new("hello world");
                    lemon_string *dup = lemon_string_concat(str, str);
                    lemon_release(str);
                    lemon_release(dup);
                    lemon_release(arr);
                    if (lemon_runtime_check_leaks() != 0) return 42;
                    return 0;
                }
                """;
        RunResult res = compileAndRunC(code, "ZeroLeakTest");
        assertEquals("Expected clean exit with zero leaks: " + res.output(), 0, res.exitCode());
    }

    @Test
    public void testNullPointerFieldAccessInLemonC() throws Exception {
        String lemonCode = """
                struct Point {
                    int x;
                    int y;
                }
                void main() {
                    struct Point *p;
                    p = null;
                    p->x = 99;
                }
                """;
        RunResult res = compileAndRunLemon(lemonCode, "NullDerefTest");
        assertTrue("Expected failure exit code on null pointer field access", res.exitCode() != 0);
        assertTrue("Expected null pointer dereference diagnostic, got: " + res.output(),
                res.output().contains("null pointer dereference"));
    }
}
