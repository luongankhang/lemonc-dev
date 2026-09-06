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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Multi-backend integration test for {@code examples/multi_backend_test}.
 *
 * <p>The fixture exercises language features across both backends in one
 * program: module imports whose public functions call each other (recursion
 * and helper calls after alias prefixing), {@code const}, short-circuit
 * boolean logic, while/for loops with break/continue, nested loops, arrays,
 * multi-level pointers, null checks, and narrowing scalar types. The same
 * source is compiled through {@code --target jvm} and {@code --target c},
 * both executables run, and their stdout must be byte-for-byte identical and
 * match the recorded expected output.</p>
 */
public class MultiBackendTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /**
     * Full expected stdout of examples/multi_backend_test/Main.lemon.
     * The 25 printf calls emit these lines; the trailing 5 lines come from
     * the pointer-walk loop over the array.
     */
    private static final String EXPECTED_OUTPUT = String.join("\n",
            "sum=42", "neg=-42", "square=225", "clamp=10", "clamp2=3",
            "modAdd=42", "bothPos=1", "eitherNeg=0", "loopSum=18", "forSum=11",
            "nested=9", "fact=120", "fact0=1", "arrLen=5", "arr2=30",
            "arrSum=150", "afterPtr=200", "throughP2=200", "multiRead=7",
            "multiWrite=42", "isNull=1", "notNull=1", "narrow=372", "big=0",
            "idxVal=10", "idxVal=20", "idxVal=30", "idxVal=40", "idxVal=50")
            + "\n";

    // =============================================== examples/multi_backend_test fixture

    @Test
    public void exampleRunsIdenticallyOnJvmAndCBackends() throws Exception {
        Path exampleDir = Path.of("examples", "multi_backend_test");
        Path mainFixture = exampleDir.resolve("Main.lemon");
        Path mathFixture = exampleDir.resolve("math.lemon");
        assertTrue("missing fixture: " + mainFixture, Files.isRegularFile(mainFixture));
        assertTrue("missing fixture: " + mathFixture, Files.isRegularFile(mathFixture));

        // Work in a temp sandbox so generated .c/.exe/.class artifacts stay
        // out of the repository; the import path stays valid because both
        // files keep their relative layout.
        Path workDir = temporaryFolder.getRoot().toPath();
        Path main = workDir.resolve("Main.lemon");
        Files.copy(mainFixture, main);
        Files.copy(mathFixture, workDir.resolve("math.lemon"));

        assertEquals("jvm compile failed", 0, compile(main, "jvm"));
        String jvmOutput = runJvm("Main");

        assertEquals("c compile failed", 0, compile(main, "c"));
        String nativeOutput = runNative(main);

        assertEquals("JVM output does not match the recorded expected output", EXPECTED_OUTPUT, jvmOutput);
        assertEquals("native output does not match the recorded expected output", EXPECTED_OUTPUT, nativeOutput);
        assertEquals("JVM and native output must match for the same Lemon source",
                jvmOutput, nativeOutput);
    }

    // =================================== regression: internal calls inside modules

    /**
     * When an imported module's public methods call one another they must be
     * rewritten to their prefixed names (m_mul inside m_fact, m_add inside
     * m_sumUpTo, recursive m_fact). Without that rewrite the merged program
     * fails semantic analysis with "undefined function" on both backends.
     */
    @Test
    public void importedModuleInternalCallsRunIdenticallyOnBothBackends() throws Exception {
        Path workDir = temporaryFolder.getRoot().toPath();
        write(workDir, "math.lemon",
                "pub int add(int a, int b) { return a + b; }\n"
                        + "pub int mul(int a, int b) { return a * b; }\n"
                        + "pub int fact(int n) {\n"
                        + "    int r;\n"
                        + "    if (n <= 1) { r = 1; } else { r = mul(n, fact(n - 1)); }\n"
                        + "    return r;\n"
                        + "}\n"
                        + "pub int sumUpTo(int n) {\n"
                        + "    int i;\n"
                        + "    int t;\n"
                        + "    t = 0;\n"
                        + "    for (i = 1; i <= n; i = i + 1) { t = add(t, i); }\n"
                        + "    return t;\n"
                        + "}\n");
        Path main = write(workDir, "ModCallMain.lemon",
                "import m = @import(\"math.lemon\");\n"
                        + "void main() {\n"
                        + "    printf(\"%d\\n\", m.fact(5));\n"
                        + "    printf(\"%d\\n\", m.fact(0));\n"
                        + "    printf(\"%d\\n\", m.sumUpTo(10));\n"
                        + "    printf(\"%d\\n\", m.add(m.mul(3, 4), 1));\n"
                        + "}\n");

        assertEquals("jvm compile failed", 0, compile(main, "jvm"));
        String jvmOutput = runJvm("ModCallMain");

        assertEquals("c compile failed", 0, compile(main, "c"));
        String nativeOutput = runNative(main);

        assertEquals("120\n1\n55\n13\n", jvmOutput);
        assertEquals("JVM and native output must match for the same Lemon source",
                jvmOutput, nativeOutput);
    }

    // ================================================================= helpers

    private int compile(Path source, String target) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = LemonC.run(new String[]{source.toString(), "--target", target},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        if (code != 0) {
            System.err.println(errors.toString(StandardCharsets.UTF_8));
        }
        return code;
    }

    /** Runs the class generated by {@code --target jvm} on a JVM. */
    private String runJvm(String className) throws Exception {
        return JvmTestSupport.run(className, new File(JvmBackend.DEFAULT_OUTPUT_DIR));
    }

    /** Runs the executable generated by {@code --target c} and normalizes newlines. */
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

    private Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
