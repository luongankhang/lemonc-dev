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
 * Full-feature cross-backend matrix for {@code examples/full_feature_matrix/}.
 *
 * <p>Every valid fixture covers a slice of the Lemon language: scalar types
 * and widening, {@code %f} printing (Java-style shortest decimal), arrays of
 * every primitive type, nested control flow, recursion and mutual recursion,
 * operators, {@code &&}/{@code ||} short-circuit semantics, raw pointers,
 * multi-file modules, printf/printLine formatting, global constants, the AST
 * optimizer, and numeric edge behavior. Each fixture is compiled with
 * {@code --target jvm} and {@code --target c}, both executables run, and the
 * stdout must be byte-for-byte identical and equal to the recorded manifest
 * ({@code expected.tsv}, base64-encoded, mirroring the root example
 * manifest). Invalid fixtures under {@code invalid/} must be rejected with the
 * same diagnostics by both backend targets.</p>
 */
public class FullFeatureMatrixTest {

    private static final Path FIXTURE_DIR = Path.of("examples", "full_feature_matrix");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** Invalid fixture name → diagnostic fragments expected on both targets. */
    private static Map<String, String[]> invalidFixtures() {
        Map<String, String[]> fixtures = new LinkedHashMap<>();
        fixtures.put("invalid_array_assign", new String[]{"arrays cannot be assigned as whole values"});
        fixtures.put("invalid_break_loop", new String[]{"break statement must be inside a loop"});
        fixtures.put("invalid_const_reassign", new String[]{"cannot assign to constant"});
        fixtures.put("invalid_dup_method", new String[]{"duplicate method"});
        fixtures.put("invalid_narrowing", new String[]{"expected int, but found double"});
        fixtures.put("invalid_return_main", new String[]{"main method does not allow return statements"});
        fixtures.put("invalid_type_mismatch", new String[]{"expected int, but found string"});
        fixtures.put("invalid_undefined_call", new String[]{"undefined function"});
        return fixtures;
    }

    // ============================================================== valid

    @Test
    public void everyMatrixFixtureRunsIdenticallyOnBothBackends() throws Exception {
        Map<String, String> manifest = loadManifest();
        TreeSet<String> fixtures = listValidFixtures();

        assertEquals("Every valid fixture needs exactly one manifest entry",
                fixtures, new TreeSet<>(manifest.keySet()));

        Path workDir = copyMatrixToTemp();
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
    public void invalidFixturesAreRejectedByBothBackendTargets() throws Exception {
        Path workDir = copyMatrixToTemp();
        for (Map.Entry<String, String[]> fixture : invalidFixtures().entrySet()) {
            File source = workDir.resolve(fixture.getKey() + ".lemon").toFile();
            assertTrue("fixture missing: " + source, source.isFile());
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

    /** Valid fixture names: Matrix*.lemon files, excluding the imported module. */
    private TreeSet<String> listValidFixtures() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(FIXTURE_DIR)) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                if (fileName.startsWith("Matrix") && fileName.endsWith(".lemon")) {
                    names.add(fileName.substring(0, fileName.length() - ".lemon".length()));
                }
            }
        }
        return names;
    }

    /** Copies all matrix sources (valid files + the imported mathlib module and
     * the invalid fixtures, flattened) into a temp sandbox so generated
     * .c/.exe never pollute examples/. */
    private Path copyMatrixToTemp() throws Exception {
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

    /** Drives the CLI for {@code --target jvm} and {@code --target c} and asserts
     * both reject the source with exit code 1 and the expected fragments. */
    private void assertRejectedByBothBackends(File source, String... expectedFragments) {
        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = LemonC.run(new String[]{source.getPath(), "--target", target},
                    new PrintStream(out), new PrintStream(err));
            String diagnostics = err.toString(StandardCharsets.UTF_8);
            assertEquals("--target " + target + " must reject " + source.getName() + ", got exit code "
                    + code + ":\n" + diagnostics, 1, code);
            for (String fragment : expectedFragments) {
                assertTrue("--target " + target + " output should contain '" + fragment + "':\n" + diagnostics,
                        diagnostics.contains(fragment));
            }
        }
    }
}
