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
 * Module/import tests validated end-to-end on both LemonC backends.
 *
 * <p>Module resolution is backend-independent, but the requirement is that the
 * same module-importing source compiles and executes identically through the
 * JVM backend (bytecode → JVM) and the C backend (C source → native
 * executable). Compile-fail cases are driven through the CLI for both
 * {@code --target jvm} and {@code --target c}.</p>
 */
public class ModuleSystemTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int compile(Path source, String target, ByteArrayOutputStream errors) {
        return LemonC.run(new String[]{source.toString(), "--target", target},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
    }

    // ============================================ valid module fixtures (both backends)

    @Test
    public void publicFunctionCanBeImportedAndExecuted() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("math.lemon"), "pub int add(int a, int b) { return a + b; }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "import math = @import(\"math.lemon\");\nvoid main() { printf(\"%d\\n\", math.add(2, 3)); }\n", StandardCharsets.UTF_8);

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            assertEquals("--target " + target + " failed: " + errors, 0, compile(main, target, errors));
        }
    }

    @Test
    public void importedPublicFunctionRunsIdenticallyOnBothBackends() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "math.lemon",
                "pub int add(int a, int b) { return a + b; }\n"
                        + "pub int mul(int a, int b) { return a * b; }\n");
        Path main = write(dir, "ModuleExecMain.lemon",
                "import math = @import(\"math.lemon\");\n"
                        + "void main() {\n"
                        + "    printf(\"%d\", math.add(2, 3));\n"
                        + "    printf(\"%d\", math.mul(4, 5));\n"
                        + "}\n");

        String jvmOutput = compileAndRunJvm(main);
        String nativeOutput = compileAndRunNative(main);
        assertEquals("520", jvmOutput);
        assertEquals("JVM and native output must match for the same Lemon source",
                jvmOutput, nativeOutput);
    }

    @Test
    public void importAliasDistinctFromModuleFileNameRunsIdenticallyOnBothBackends() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "calculator.lemon",
                "pub int add(int a, int b) { return a + b; }\n");
        Path main = write(dir, "AliasExecMain.lemon",
                "import calc = @import(\"calculator.lemon\");\n"
                        + "void main() { printf(\"%d\", calc.add(8, 4)); }\n");

        String jvmOutput = compileAndRunJvm(main);
        String nativeOutput = compileAndRunNative(main);
        assertEquals("12", jvmOutput);
        assertEquals("JVM and native output must match for the same Lemon source",
                jvmOutput, nativeOutput);
    }

    // ============================================ compile failures (both backends)

    @Test
    public void privateFunctionIsNotExported() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("math.lemon"), "int secret() { return 42; }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "import math = @import(\"math.lemon\");\nvoid main() { printf(\"%d\\n\", math.secret()); }\n", StandardCharsets.UTF_8);

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            assertEquals("--target " + target + " must reject private function access: " + errors,
                    1, compile(main, target, errors));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("undefined function"));
        }
    }

    @Test
    public void missingModuleHasDiagnostic() throws Exception {
        Path main = temporaryFolder.getRoot().toPath().resolve("main.lemon");
        Files.writeString(main, "import missing = @import(\"missing.lemon\");\nvoid main() { }\n", StandardCharsets.UTF_8);

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            assertEquals("--target " + target + " must reject a missing module: " + errors,
                    1, compile(main, target, errors));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("module not found"));
        }
    }

    @Test
    public void circularModuleDependencyHasDiagnostic() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("a.lemon"), "import b = @import(\"b.lemon\");\nvoid a() { }\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.lemon"), "import a = @import(\"a.lemon\");\nvoid b() { }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "import a = @import(\"a.lemon\");\nvoid main() { }\n", StandardCharsets.UTF_8);

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            assertEquals("--target " + target + " must reject a circular dependency: " + errors,
                    1, compile(main, target, errors));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("circular module dependency"));
        }
    }

    // ================================================================ helpers

    private Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** Compiles with {@code --target jvm}, then runs the generated class on a JVM. */
    private String compileAndRunJvm(Path main) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals("jvm compile failed: " + errors, 0, compile(main, "jvm", errors));
        return JvmTestSupport.run(baseName(main), new File(JvmBackend.DEFAULT_OUTPUT_DIR));
    }

    /** Compiles with {@code --target c} through the native toolchain and runs the executable. */
    private String compileAndRunNative(Path main) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals("native compile failed: " + errors, 0, compile(main, "c", errors));
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exe = main.getParent().resolve(baseName(main) + (isWindows ? ".exe" : ""));
        assertTrue("Executable does not exist: " + exe, Files.isRegularFile(exe));

        Process process = new ProcessBuilder(exe.toAbsolutePath().toString()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("native process exited with non-zero status", 0, process.waitFor());
        return output.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".lemon".length());
    }
}
