import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deep pointer-semantics matrix for {@code examples/pointer_full/} on both
 * LemonC backends.
 *
 * <p>Every valid fixture is compiled with {@code --target jvm} and
 * {@code --target c}, both executables run, and stdout must be byte-for-byte
 * identical and equal to the recorded manifest ({@code expected.tsv},
 * base64-encoded like the root example manifest). The fixtures cover
 * declaration, address-of, dereference read/write, mutation of the original
 * variable through a pointer, pointer parameters and returns, pointer-to-
 * pointer and deeper chains, pointer assignment/aliasing, comparison,
 * null handling, pointers steering control flow, pointer/lifetime interplay,
 * and optimizer safety (folding/dead-branch elimination must not drop
 * pointer side effects). Invalid fixtures under {@code invalid/} must be
 * rejected by both backend targets, and the diagnostics themselves must be
 * identical across the two targets (single front-end, so the error text is
 * target-independent).</p>
 */
public class PointerMultiBackendTest {

    private static final Path FIXTURE_DIR = Path.of("examples", "pointer_full");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** Invalid fixture name → diagnostic fragment expected on both targets. */
    private static Map<String, String[]> invalidFixtures() {
        Map<String, String[]> fixtures = new LinkedHashMap<>();
        fixtures.put("invalid_addr_of_array",
                new String[]{"cannot take the address of 'arr'"});
        fixtures.put("invalid_addr_of_const",
                new String[]{"cannot take the address of 'FIXED'"});
        fixtures.put("invalid_addr_of_expr",
                new String[]{"address-of requires a local variable"});
        fixtures.put("invalid_addr_of_param",
                new String[]{"cannot take the address of parameter"});
        fixtures.put("invalid_deref_nonptr",
                new String[]{"cannot dereference non-pointer type int"});
        fixtures.put("invalid_deref_store_type",
                new String[]{"expected int, but found double"});
        fixtures.put("invalid_deref_write_pointer",
                new String[]{"assignment through a dereference must store a value scalar"});
        fixtures.put("invalid_level_mismatch",
                new String[]{"expected int***, but found int**"});
        fixtures.put("invalid_ordering_compare",
                new String[]{"expected numeric operands, but found int* and int*"});
        fixtures.put("invalid_pass_mismatch",
                new String[]{"expected float*, but found int*"});
        fixtures.put("invalid_ptr_arith_add",
                new String[]{"invalid pointer arithmetic"});
        fixtures.put("invalid_ptr_arith_sub",
                new String[]{"invalid pointer arithmetic"});
        fixtures.put("invalid_ptr_compare_mismatch",
                new String[]{"expected int*, but found float*"});
        fixtures.put("invalid_ptr_to_array",
                new String[]{"syntax error"});
        fixtures.put("invalid_ptr_to_string",
                new String[]{"unsupported pointer type 'string*'"});
        fixtures.put("invalid_truthiness",
                new String[]{"expected bool, but found int*"});
        return fixtures;
    }

    // ============================================================== valid

    @Test
    public void everyPointerFixtureRunsIdenticallyOnBothBackends() throws Exception {
        Map<String, String> manifest = loadManifest();
        TreeSet<String> fixtures = listValidFixtures();

        assertEquals("Every valid fixture needs exactly one manifest entry",
                fixtures, new TreeSet<>(manifest.keySet()));

        Path workDir = copyFixturesToTemp();
        for (String name : fixtures) {
            Path source = workDir.resolve(name + ".lemon");
            assertTrue("fixture missing: " + source, Files.isRegularFile(source));

            assertEquals(name + ": jvm compile failed", 0, compile(source, "jvm"));
            String jvmOutput = JvmTestSupport.run(name, new File(JvmBackend.DEFAULT_OUTPUT_DIR));

            assertEquals(name + ": c compile failed", 0, compile(source, "c"));
            String nativeOutput = runNative(source);

            assertEquals(name + ": JVM output must match the recorded manifest",
                    normalize(manifest.get(name)), jvmOutput);
            assertEquals(name + ": native output must match the recorded manifest",
                    normalize(manifest.get(name)), nativeOutput);
            assertEquals(name + ": JVM and native output must match for the same Lemon source",
                    jvmOutput, nativeOutput);
        }
    }

    // ============================================================ invalid

    @Test
    public void invalidPointerSourcesAreRejectedByBothBackendTargets() throws Exception {
        Path workDir = copyFixturesToTemp();
        for (Map.Entry<String, String[]> fixture : invalidFixtures().entrySet()) {
            Path source = workDir.resolve(fixture.getKey() + ".lemon");
            assertTrue("fixture missing: " + source, Files.isRegularFile(source));
            assertRejectedByBothBackends(source, fixture.getValue());
        }
    }

    // =============================================================== helpers

    private Map<String, String> loadManifest() throws Exception {
        Path manifest = FIXTURE_DIR.resolve("expected.tsv");
        assertTrue("expected output manifest should exist: " + manifest, Files.isRegularFile(manifest));
        Map<String, String> outputs = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.US_ASCII)) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            assertEquals("Manifest line must contain name and base64 output: " + line, 2, parts.length);
            outputs.put(parts[0], new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        }
        return outputs;
    }

    /** Valid fixture names: pf_*.lemon files directly under the fixture dir. */
    private TreeSet<String> listValidFixtures() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(FIXTURE_DIR)) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                if (fileName.startsWith("pf_") && fileName.endsWith(".lemon")) {
                    names.add(fileName.substring(0, fileName.length() - ".lemon".length()));
                }
            }
        }
        return names;
    }

    /** Copies valid fixtures and the invalid/ fixtures into a temp sandbox so
     * generated .c/.exe/.class files never pollute examples/. */
    private Path copyFixturesToTemp() throws Exception {
        Path workDir = temporaryFolder.getRoot().toPath();
        copyLemonFiles(FIXTURE_DIR, workDir);
        copyLemonFiles(FIXTURE_DIR.resolve("invalid"), workDir);
        return workDir;
    }

    private void copyLemonFiles(Path fromDir, Path toDir) throws Exception {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(fromDir)) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".lemon")) {
                    Files.copy(file, toDir.resolve(fileName));
                }
            }
        }
    }

    /** Drives the CLI for one target; returns the compile exit code and prints
     * diagnostics on failure. */
    private int compile(Path source, String target) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = LemonC.run(new String[]{source.toString(), "--target", target},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        if (code != 0) {
            System.err.println(target + " compile diagnostics for " + source.getFileName() + ":\n"
                    + errors.toString(StandardCharsets.UTF_8));
        }
        return code;
    }

    /** Runs the executable produced by {@code --target c} and normalizes newlines. */
    private String runNative(Path source) throws Exception {
        String name = source.getFileName().toString();
        String baseName = name.substring(0, name.length() - ".lemon".length());
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exe = source.getParent().resolve(baseName + (isWindows ? ".exe" : ""));
        assertTrue("Executable does not exist: " + exe, Files.isRegularFile(exe));

        Process process = new ProcessBuilder(exe.toAbsolutePath().toString()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(baseName + ": native process exited with non-zero status", 0, process.waitFor());
        return normalize(output);
    }

    private String normalize(String value) {
        return value == null ? null : value.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** Both backend targets must reject the source with exit code 1, contain the
     * expected diagnostic fragments, and produce byte-identical diagnostics. */
    private void assertRejectedByBothBackends(Path source, String... expectedFragments) {
        String previous = null;
        String collected = "";
        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = LemonC.run(new String[]{source.toString(), "--target", target},
                    new PrintStream(out), new PrintStream(err));
            String diagnostics = err.toString(StandardCharsets.UTF_8);
            assertEquals("--target " + target + " must reject " + source.getFileName()
                    + ", got exit code " + code + ":\n" + diagnostics, 1, code);
            assertTrue("--target " + target + " produced no diagnostics for " + source.getFileName(),
                    !diagnostics.isBlank());
            if (previous != null) {
                assertEquals("diagnostics for " + source.getFileName()
                        + " must be identical across backends", previous, diagnostics);
            }
            previous = diagnostics;
            collected = diagnostics;
            for (String fragment : expectedFragments) {
                assertTrue("--target " + target + " output should contain '" + fragment + "':\n" + diagnostics,
                        diagnostics.contains(fragment));
            }
        }
        assertTrue("no diagnostics captured for " + source.getFileName(), !collected.isBlank());
    }
}
