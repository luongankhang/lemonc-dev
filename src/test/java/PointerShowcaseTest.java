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
 * End-to-end multi-backend test for {@code examples/pointer_showcase/pointer_showcase.lemon}.
 * Compiles to both JVM and C backends, verifies execution, and asserts byte-for-byte output parity.
 */
public class PointerShowcaseTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final String EXPECTED_OUTPUT = String.join("\n",
            "=== Pointer Showcase ===",
            "case1_x=20",
            "case2_x=30",
            "case2_deref_p=30",
            "case2_deref_pp=30",
            "case3_x_after_setValue_addr=40",
            "case3_x_after_setValue_ptr=50",
            "case3_x_after_setValue_deref_pp=60",
            "threshold_exceeded=0",
            "threshold_exceeded=1",
            "larger_val=75",
            "y_after_mutation=80",
            "alias_equals_p=1",
            "x_after_alias_write=90",
            "p_equals_alias_after_repoint=0",
            "via_pp_pointing_to_y=80",
            "null_check=1",
            "null_not_equal_valid=1",
            "isNull_helper_null=1",
            "isNull_helper_valid=0",
            "double_val=2.5",
            "bool_via_ptr=1",
            "long_val=9999999",
            "byte_val=42",
            "countdown=3",
            "countdown=2",
            "countdown=1",
            "countdown_final=0",
            "data_len=4",
            "array_sum=147",
            "array_max=67",
            "inside_block=333",
            "outside_block=333",
            "tags_len=2",
            "=== End Pointer Showcase ===") + "\n";

    @Test
    public void pointerShowcaseRunsIdenticallyOnJvmAndCBackends() throws Exception {
        Path fixture = Path.of("examples", "pointer_showcase", "pointer_showcase.lemon");
        assertTrue("fixture missing: " + fixture, Files.isRegularFile(fixture));

        Path workDir = temporaryFolder.getRoot().toPath();
        Path main = workDir.resolve("pointer_showcase.lemon");
        Files.copy(fixture, main);

        // Compile and run JVM
        assertEquals("jvm compile failed", 0, compile(main, "jvm"));
        String jvmOutput = runJvm("pointer_showcase");

        // Compile and run C
        assertEquals("c compile failed", 0, compile(main, "c"));
        String nativeOutput = runNative(main);

        // Output parity assertions
        assertEquals("JVM output does not match expected output", EXPECTED_OUTPUT, jvmOutput);
        assertEquals("Native output does not match expected output", EXPECTED_OUTPUT, nativeOutput);
        assertEquals("JVM and Native output must be byte-for-byte identical", jvmOutput, nativeOutput);
    }

    private int compile(Path source, String target) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = LemonC.run(new String[]{source.toString(), "--target", target},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        if (code != 0) {
            System.err.println(errors.toString(StandardCharsets.UTF_8));
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
}
