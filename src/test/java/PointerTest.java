import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.ast.Ast;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * C-like raw pointers (int*, int**, null, &x, *p, *p = v) validated
 * end-to-end on both LemonC backends.
 *
 * <p>Pointers are unmanaged raw pointers: they never receive ARC retain or
 * release operations, taking the address of a local only stays valid inside
 * the frame that owns it (returning such an address is rejected), and a null
 * dereference is a defined runtime error on both backends.</p>
 *
 * <p>Every valid fixture in {@code examples/pointer/} is compiled and executed
 * on the JVM backend and on the C backend (gcc/clang) and must produce the
 * same output. Every invalid fixture in {@code examples/pointer/invalid/} must
 * be rejected by both {@code --target jvm} and {@code --target c} with the
 * expected diagnostics.</p>
 */
public class PointerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** Valid fixture → expected observable output (identical on both backends). */
    private static Map<String, String> validFixtures() {
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("pointer_basic", "42100");
        fixtures.put("pointer_address_of", "77");
        fixtures.put("pointer_dereference", "2010");
        fixtures.put("pointer_assignment", "10201030");
        fixtures.put("pointer_multi_level", "74040");
        fixtures.put("pointer_null", "1421");
        fixtures.put("pointer_comparison", "1011");
        fixtures.put("pointer_functions", "1212100");
        fixtures.put("pointer_param_store", "42");
        return fixtures;
    }

    /** Invalid fixture → diagnostic fragments that must appear on both targets. */
    private static Map<String, String[]> invalidFixtures() {
        Map<String, String[]> fixtures = new LinkedHashMap<>();
        fixtures.put("pointer_invalid_deref",
                new String[]{"cannot dereference non-pointer type int"});
        fixtures.put("pointer_invalid_address_of",
                new String[]{"cannot take the address of this expression"});
        fixtures.put("pointer_type_mismatch",
                new String[]{"expected int*, but found float*"});
        fixtures.put("pointer_invalid_comparison",
                new String[]{"expected int*, but found float*"});
        fixtures.put("pointer_dangling_local",
                new String[]{"cannot return pointer to local variable"});
        fixtures.put("pointer_invalid_operation",
                new String[]{"invalid pointer arithmetic"});
        fixtures.put("pointer_escape_local",
                new String[]{"cannot store pointer to local variable through dereference"});
        fixtures.put("pointer_escape_param",
                new String[]{"cannot store pointer to local variable through dereference"});
        fixtures.put("pointer_escape_multi",
                new String[]{"cannot store pointer to local variable through dereference"});
        return fixtures;
    }

    // =============================================================== valid

    @Test
    public void everyPointerFixtureRunsIdenticallyOnBothBackends() throws Exception {
        for (Map.Entry<String, String> fixture : validFixtures().entrySet()) {
            String name = fixture.getKey();
            String expected = fixture.getValue();
            File source = new File("examples/pointer/" + name + ".lemon");
            assertTrue("fixture missing: " + source, source.isFile());

            String jvmOutput = JvmTestSupport.run(JvmTestSupport.compile(source));
            assertEquals(name + ": JVM output", expected, jvmOutput);

            IrModule module = lowerFile(source);
            String nativeOutput = runNative(module);
            assertEquals(name + ": native output must match JVM for the same source",
                    jvmOutput, nativeOutput);
            assertEquals(name + ": expected output", expected, nativeOutput);
        }
    }

    /** C codegen stays close to the source: native &, *, and NULL, no wrappers. */
    @Test
    public void generatedCShowsIdiomaticPointerCode() throws Exception {
        File source = new File("examples/pointer/pointer_basic.lemon");
        String c = new CBackend().generate(lowerFile(source));
        assertTrue(c, c.contains("= &(value);"));
        assertTrue(c, c.contains("lemon_require_ptr(ptr), ptr)"));

        File nullSource = new File("examples/pointer/pointer_null.lemon");
        String cNull = new CBackend().generate(lowerFile(nullSource));
        assertTrue(cNull, cNull.contains("NULL"));
    }

    /**
     * The JVM represents an {@code int*} as an {@code int[]} cell reference: a
     * generated function parameter of type int* has descriptor {@code [I} and
     * pointer-typed returns are array descriptors too; address-taken locals are
     * materialized with {@code newarray int}.
     */
    @Test
    public void jvmPointerSignaturesUseCellArrayDescriptors() throws Exception {
        File source = new File("examples/pointer/pointer_functions.lemon");
        byte[] classBytes = JvmTestSupport.compile(source).classBytes();
        assertTrue(JvmTestSupport.hasMethod(classBytes, "bump", "([I)I"));
        assertTrue(JvmTestSupport.hasMethod(classBytes, "selectPtr", "([I[II)[I"));
        assertTrue(JvmTestSupport.hasNewArray(classBytes, "int"));
    }

    /** Dereferencing keeps every scalar type (long/double/float/char) exact. */
    @Test
    public void pointerDerefPreservesAllScalarTypesOnBothBackends() throws Exception {
        String source = ""
                + "void main() {\n"
                + "    long a; long* pl;\n"
                + "    double d; double* pd;\n"
                + "    float f; float* pf;\n"
                + "    char c; char* pc;\n"
                + "    a = 9876543210;\n"
                + "    pl = &a;\n"
                + "    d = 2.5;\n"
                + "    pd = &d;\n"
                + "    f = 1.5;\n"
                + "    pf = &f;\n"
                + "    c = 'A';\n"
                + "    pc = &c;\n"
                + "    if (*pl == 9876543210) { printf(\"%d\", 1); } else { printf(\"%d\", 0); }\n"
                + "    if (*pd == 2.5) { printf(\"%d\", 1); } else { printf(\"%d\", 0); }\n"
                + "    if (*pf == 1.5) { printf(\"%d\", 1); } else { printf(\"%d\", 0); }\n"
                + "    printf(\"%d\", *pc);\n"
                + "    *pl = *pl + 1;\n"
                + "    *pd = *pd * 2.0;\n"
                + "    if (*pl == 9876543211) { printf(\"%d\", 1); } else { printf(\"%d\", 0); }\n"
                + "    if (*pd == 5.0) { printf(\"%d\", 1); } else { printf(\"%d\", 0); }\n"
                + "}\n";
        assertEquals("1116511", JvmTestSupport.compileAndRun("PointerTypedMain", source));
        String nativeOutput = runNative(lower(source));
        assertEquals("JVM and native output must match for the same Lemon source",
                "1116511", nativeOutput);
    }

    /** Dereferencing null is a defined runtime error on both backends. */
    @Test
    public void nullPointerDereferenceFailsAtRuntimeOnBothBackends() throws Exception {
        String source = ""
                + "void main() {\n"
                + "    int* p; int x;\n"
                + "    p = null;\n"
                + "    x = *p;\n"
                + "    printf(\"%d\", x);\n"
                + "}\n";

        // JVM: the program compiles, but running it traps with a clear message.
        JvmTestSupport.CompiledClass compiled = JvmTestSupport.compile("PointerNullDerefMain", source);
        String java = new File(new File(System.getProperty("java.home"), "bin"),
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").getPath();
        ProcessResult jvmResult = runProcessExpectingFailure(
                new String[]{java, "-cp", compiled.classDir().getPath(), compiled.className()});
        assertTrue("JVM should report a null dereference: " + jvmResult.output,
                jvmResult.output.contains("null pointer dereference"));

        // C: same source, native build, nonzero exit with the runtime message.
        IrModule module = lower(source);
        ProcessResult nativeResult = runNativeExpectingFailure(module);
        assertTrue("native should report a null dereference: " + nativeResult.output,
                nativeResult.output.contains("null pointer dereference"));
    }

    // ============================================================== invalid

    @Test
    public void invalidPointerSourcesAreRejectedByBothBackendTargets() throws Exception {
        for (Map.Entry<String, String[]> fixture : invalidFixtures().entrySet()) {
            File source = new File("examples/pointer/invalid/" + fixture.getKey() + ".lemon");
            assertTrue("fixture missing: " + source, source.isFile());
            assertRejectedByBothBackends(source, fixture.getValue());
        }
    }

    // =============================================================== helpers

    private IrModule lower(String source) throws Exception {
        File dir = temporaryFolder.getRoot();
        File file = new File(dir, "PointerSource.lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        return lowerFile(file);
    }

    private IrModule lowerFile(File file) throws Exception {
        Parser parser = new Parser(new Lexer(file));
        Ast.Program.T program = parser.parse();
        new ModuleLoader().resolve(program, file.toPath());
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        assertTrue("semantic errors: " + semantic.getDiagnostics(), semantic.passOrNot());
        program = new AstOptimizer().optimize(program);
        return new AstToIrLowerer().lower(program);
    }

    /** Compiles LemonIR to C, builds it natively, runs it, returns stdout. */
    private String runNative(IrModule module) throws Exception {
        Path runtimeRoot = Path.of("runtime").toAbsolutePath();
        Path sourceFile = Files.createTempFile("lemonc-pointer-native", ".c");
        new CBackend().generate(module, sourceFile);
        Path exe = Files.createTempFile("lemonc-pointer-native", ".exe");
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

    private ProcessResult runNativeExpectingFailure(IrModule module) throws Exception {
        Path runtimeRoot = Path.of("runtime").toAbsolutePath();
        Path sourceFile = Files.createTempFile("lemonc-pointer-native-fail", ".c");
        new CBackend().generate(module, sourceFile);
        Path exe = Files.createTempFile("lemonc-pointer-native-fail", ".exe");
        NativeToolchain toolchain = NativeToolchain.discover();
        try {
            Path executable = toolchain.compile(sourceFile, runtimeRoot.resolve("lemon_runtime.c"), exe);
            return runProcessExpectingFailure(new String[]{executable.toString()});
        } finally {
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(exe);
        }
    }

    private ProcessResult runProcessExpectingFailure(String[] command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertTrue("expected a failing process but exit code was " + code + ":\n" + output, code != 0);
        return new ProcessResult(code, output.replace("\r\n", "\n").replace("\r", "\n"));
    }

    /**
     * Drives the full CLI for {@code --target jvm} and {@code --target c} and
     * asserts both backends reject the source with exit code 1 and the expected
     * diagnostic fragments.
     */
    private void assertRejectedByBothBackends(File main, String... expectedFragments) throws Exception {
        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = LemonC.run(new String[]{main.getPath(), "--target", target},
                    new PrintStream(out), new PrintStream(err));
            String diagnostics = err.toString(StandardCharsets.UTF_8);
            assertEquals("--target " + target + " must reject " + main.getName() + ", got exit code "
                    + code + ":\n" + diagnostics, 1, code);
            for (String fragment : expectedFragments) {
                assertTrue("--target " + target + " output should contain '" + fragment + "':\n" + diagnostics,
                        diagnostics.contains(fragment));
            }
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
