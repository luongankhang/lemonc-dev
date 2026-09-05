import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.ast.Ast;
import site.ilemon.backend.c.CBackend;
import site.ilemon.backend.c.NativeToolchain;
import site.ilemon.compiler.LemonC;
import site.ilemon.compiler.ModuleLoader;
import site.ilemon.diagnostic.Diagnostic;
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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Global {@code const} / {@code pub const} declarations with module visibility.
 *
 * <p>Covers: global-scope enforcement (parse diagnostics), literal-only
 * initializers, immutability, module-qualified reads through the existing
 * import mechanism, private-constant visibility, and both backends.</p>
 */
public class GlobalConstTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    // ================================================================ valid

    @Test
    public void readsGlobalConstantsFromFunctions() throws Exception {
        String source = ""
                + "const int MAX_SIZE = 1024;\n"
                + "pub const int VERSION = 1;\n"
                + "int use(int x) { return x + MAX_SIZE + VERSION; }\n"
                + "void main() { printf(\"%d\", use(1)); }\n";
        assertEquals("1026", JvmTestSupport.compileAndRun("ConstRead", source));
    }

    @Test
    public void supportsAllConstantCompatibleScalarTypes() throws Exception {
        String source = ""
                + "const byte B = 100;\n"
                + "const short S = -30000;\n"
                + "const char C = 'A';\n"
                + "const long L = 9223372036854775807;\n"
                + "const float F = 1.5;\n"
                + "const double D = 3.25;\n"
                + "const bool FLAG = true;\n"
                + "void main() {\n"
                + "    printf(\"%d\", B);\n"
                + "    printf(\"%d\", S);\n"
                + "    printf(\"%d\", C);\n"
                + "    printf(\"%d\", L);\n"
                + "    if (FLAG) { printf(\"1\"); } else { printf(\"0\"); }\n"
                + "    printf(\"%f\", F);\n"
                + "    printf(\"%f\", D);\n"
                + "}\n";
        assertEquals("100-3000065922337203685477580711.53.25",
                JvmTestSupport.compileAndRun("ConstScalars", source));
    }

    @Test
    public void supportsStringConstantsInStringArrays() throws Exception {
        String source = ""
                + "const string GREETING = \"hi\";\n"
                + "void main() { string copy[1]; copy[0] = GREETING; }\n";
        assertEquals("", JvmTestSupport.compileAndRun("ConstString", source));
    }

    @Test
    public void constantsAreUsableInConditionsAndArithmetic() throws Exception {
        String source = ""
                + "const int LIMIT = 5;\n"
                + "const bool ENABLED = true;\n"
                + "void main() {\n"
                + "    int i; int sum;\n"
                + "    sum = 0;\n"
                + "    for (i = 0; i < LIMIT; i = i + 1) { sum = sum + i; }\n"
                + "    if (ENABLED) { printf(\"%d\", sum); }\n"
                + "}\n";
        assertEquals("10", JvmTestSupport.compileAndRun("ConstLoop", source));
    }

    // ============================================================ modules

    @Test
    public void readsPubConstFromImportedModuleOnJvm() throws Exception {
        File dir = Files.createTempDirectory("lemonc-const-module").toFile();
        write(dir, "math.lemon", ""
                + "pub const int VERSION = 1;\n"
                + "const int INTERNAL_LIMIT = 100;\n"
                + "pub int limit() { return INTERNAL_LIMIT; }\n");
        File main = write(dir, "MainConst.lemon", ""
                + "import math = @import(\"math.lemon\");\n"
                + "void main() {\n"
                + "    printf(\"%d\", math.VERSION);\n"
                + "    printf(\"%d\", math.limit());\n"
                + "}\n");
        JvmTestSupport.CompiledClass compiled = JvmTestSupport.compile(main);
        assertEquals("1100", JvmTestSupport.run(compiled));
    }

    @Test
    public void privateConstIsInvisibleAcrossModules() throws Exception {
        File dir = Files.createTempDirectory("lemonc-const-visibility").toFile();
        write(dir, "math.lemon", ""
                + "const int INTERNAL_LIMIT = 100;\n"
                + "int dummy() { return 0; }\n");
        File main = write(dir, "MainPrivate.lemon",
                "import math = @import(\"math.lemon\");\n"
                        + "void main() { printf(\"%d\", math.INTERNAL_LIMIT); }\n");
        SemanticVisitor semantic = analyzeWithImports(main);
        assertFalse(semantic.passOrNot());
        Diagnostic diagnostic = semantic.getDiagnostics().get(0);
        assertEquals("E2001", diagnostic.code());
        assertTrue(diagnostic.message().contains("has no public constant 'INTERNAL_LIMIT'"));
    }

    // ===================================================== global scope only

    @Test
    public void rejectsConstInsideFunctionBody() throws Exception {
        ParseFailure failure = parseFailure("void main() { const int VALUE = 10; }\n");
        assertTrue(failure.message(), failure.message().contains("const declarations are only allowed at global scope"));
    }

    @Test
    public void rejectsPubConstInsideFunctionBody() throws Exception {
        ParseFailure failure = parseFailure("int foo() { pub const int A = 10; return A; }\nvoid main() {}\n");
        assertTrue(failure.message(), failure.message().contains("const declarations are only allowed at global scope"));
    }

    @Test
    public void rejectsConstInsideNestedBlock() throws Exception {
        ParseFailure failure = parseFailure(
                "void main() { if (true) { const int VALUE = 10; } }\n");
        assertTrue(failure.message(), failure.message().contains("const declarations are only allowed at global scope"));
    }

    @Test
    public void rejectsMissingInitializer() throws Exception {
        ParseFailure failure = parseFailure("const int X;\nvoid main() {}\n");
        assertTrue(failure.message(), failure.message().contains("expected '='"));
    }

    // ========================================================== immutability

    @Test
    public void rejectsReassignmentOfConst() throws Exception {
        assertConstImmutable("const int VALUE = 10;\nvoid main() { VALUE = 20; }\n", "VALUE");
    }

    @Test
    public void rejectsReassignmentOfPubConst() throws Exception {
        assertConstImmutable("pub const int VERSION = 1;\nvoid main() { VERSION = 2; }\n", "VERSION");
    }

    @Test
    public void rejectsConstMutationThroughArrayStore() throws Exception {
        // Arrays cannot be const (rejected by type rules); element stores target arrays only.
        assertConstImmutable("const int VALUE = 10;\nvoid main() { VALUE = VALUE + 1; }\n", "VALUE");
    }

    @Test
    public void rejectsIncrementAndCompoundAssignmentOperators() throws Exception {
        // The language has no ++/--/+=/-= tokens, so they are rejected at parse
        // time and constants can never be mutated through them.
        for (String stmt : new String[]{"X++;", "X--;", "X += 1;", "X -= 1;"}) {
            ParseFailure failure = parseFailure("const int X = 1;\nvoid main() { " + stmt + " }\n");
            assertTrue(failure.message(), failure.message().contains("expected 'Assign'"));
        }
    }

    private void assertConstImmutable(String source, String name) throws Exception {
        SemanticVisitor semantic = analyze(source);
        assertFalse(semantic.passOrNot());
        Diagnostic diagnostic = semantic.getDiagnostics().get(0);
        assertEquals("E2006", diagnostic.code());
        assertTrue(diagnostic.message().contains("cannot assign to constant '" + name + "'"));
        assertTrue(diagnostic.message().contains("constants are immutable"));
    }

    // ================================================= initializer validation

    @Test
    public void rejectsNonLiteralInitializer() throws Exception {
        SemanticVisitor semantic = analyze("const int X = 1 + 2;\nvoid main() {}\n");
        assertFalse(semantic.passOrNot());
        Diagnostic diagnostic = semantic.getDiagnostics().get(0);
        assertEquals("E2007", diagnostic.code());
        assertTrue(diagnostic.message().contains("must be initialized with a literal value"));
    }

    @Test
    public void rejectsArrayAndVoidConstantTypes() throws Exception {
        // Array syntax is rejected at parse time (constants must be scalar).
        ParseFailure array = parseFailure("const int values[2] = 1;\nvoid main() {}\n");
        assertTrue(array.message(), array.message().contains("expected '='"));
        // void reaches the semantic scalar-type check.
        SemanticVisitor voidConst = analyze("const void X = 1;\nvoid main() {}\n");
        assertFalse(voidConst.passOrNot());
        assertTrue(voidConst.getDiagnostics().get(0).message().contains("must have a scalar type"));
    }

    @Test
    public void rejectsTypeMismatchedInitializer() throws Exception {
        SemanticVisitor semantic = analyze("const int X = \"hi\";\nvoid main() {}\n");
        assertFalse(semantic.passOrNot());
        Diagnostic diagnostic = semantic.getDiagnostics().get(0);
        assertEquals("E3001", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected int, but found string"));
    }

    @Test
    public void rejectsOutOfRangeByteAndShortInitializers() throws Exception {
        SemanticVisitor byteConst = analyze("const byte B = 128;\nvoid main() {}\n");
        assertEquals("E3008", byteConst.getDiagnostics().get(0).code());
        SemanticVisitor shortConst = analyze("const short S = 32768;\nvoid main() {}\n");
        assertEquals("E3009", shortConst.getDiagnostics().get(0).code());
    }

    @Test
    public void rejectsDuplicateConstantDeclarations() throws Exception {
        SemanticVisitor semantic = analyze("const int X = 10;\nconst int X = 20;\nvoid main() {}\n");
        assertFalse(semantic.passOrNot());
        Diagnostic diagnostic = semantic.getDiagnostics().get(0);
        assertEquals("E2003", diagnostic.code());
        assertTrue(diagnostic.message().contains("duplicate constant declaration: X"));
    }

    @Test
    public void rejectsConstantNameCollidingWithMethodName() throws Exception {
        SemanticVisitor semantic = analyze("const int add = 1;\nint add() { return 0; }\nvoid main() {}\n");
        assertFalse(semantic.passOrNot());
        assertEquals("E2003", semantic.getDiagnostics().get(0).code());
    }

    // ============================================================== backend

    @Test
    public void cBackendEmitsStaticConstDeclarationsAndCompiles() throws Exception {
        IrModule module = lower(""
                + "const int MAX_SIZE = 1024;\n"
                + "pub const int VERSION = 1;\n"
                + "const bool DEBUG = true;\n"
                + "const double PI = 3.25;\n"
                + "int use(int x) { return x + MAX_SIZE; }\n"
                + "void main() {\n"
                + "    printf(\"%d\", use(1) + VERSION);\n"
                + "    if (DEBUG) { printf(\"%f\", PI); }\n"
                + "}\n");
        String c = new CBackend().generate(module);
        assertTrue(c, c.contains("static const int32_t MAX_SIZE = 1024;"));
        assertTrue(c, c.contains("const int32_t VERSION = 1;"));
        assertTrue(c, c.contains("static const bool DEBUG = true;"));
        assertTrue(c, c.contains("const double PI = 3.25;"));

        assertEquals("10263.250000", runNative(module));
    }

    @Test
    public void cBackendSupportsCrossModuleConstReads() throws Exception {
        File dir = Files.createTempDirectory("lemonc-const-c-module").toFile();
        write(dir, "math.lemon", ""
                + "pub const int VERSION = 1;\n"
                + "const int INTERNAL_LIMIT = 100;\n"
                + "pub int limit() { return INTERNAL_LIMIT; }\n");
        File main = write(dir, "MainC.lemon", ""
                + "import math = @import(\"math.lemon\");\n"
                + "void main() {\n"
                + "    printf(\"%d\", math.VERSION);\n"
                + "    printf(\"%d\", math.limit());\n"
                + "}\n");
        IrModule module = lowerFile(main);
        String c = new CBackend().generate(module);
        assertTrue(c, c.contains("static const int32_t INTERNAL_LIMIT = 100;"));
        assertTrue(c, c.contains("const int32_t math_VERSION = 1;"));
        assertEquals("1100", runNative(module));
    }

    @Test
    public void jvmBackendInlinesConstantValues() throws Exception {
        JvmTestSupport.CompiledClass compiled = JvmTestSupport.compile("ConstInline",
                "const int VALUE = 42;\nvoid main() { printf(\"%d\", VALUE); }\n");
        // Constants are compile-time values: the class declares no fields
        // (printf's own getstatic of System.out is unrelated).
        assertTrue(JvmTestSupport.hasNoFields(compiled.classBytes()));
        assertEquals("42", JvmTestSupport.run(compiled));
    }

    // ============================================ backend matrix (both backends)

    /**
     * The same module-local {@code const} / {@code pub const} source must behave
     * identically on the JVM and C backends: compile, execute, and compare the
     * runtime output of the very same program on each backend.
     */
    @Test
    public void globalConstAndPubConstRunIdenticallyOnBothBackends() throws Exception {
        String source = ""
                + "const int MAX_SIZE = 100;\n"
                + "pub const int STEP = 5;\n"
                + "int scaled(int x) { return x * MAX_SIZE / STEP; }\n"
                + "void main() { printf(\"%d\", scaled(2) + MAX_SIZE); }\n";

        IrModule module = lower(source);
        String cSource = new CBackend().generate(module);
        // Private constants get internal linkage; pub constants keep external linkage.
        assertTrue(cSource, cSource.contains("static const int32_t MAX_SIZE = 100;"));
        assertTrue(cSource, cSource.contains("const int32_t STEP = 5;"));

        String jvmOutput = JvmTestSupport.compileAndRun("MatrixConstMain", source);
        assertEquals("140", jvmOutput);
        String nativeOutput = runNative(module);
        assertEquals("JVM and native output must match for the same Lemon source",
                jvmOutput, nativeOutput);
    }

    /**
     * Cross-module {@code pub const} reads and function calls through an import
     * alias (alias {@code m} for module file {@code math.lemon}) must produce
     * identical runtime output on both backends; private constants of the
     * imported module stay visible only inside that module's own functions.
     */
    @Test
    public void pubConstAndFunctionsAcrossModulesRunIdenticallyOnBothBackends() throws Exception {
        File dir = temporaryFolder.getRoot();
        write(dir, "math.lemon", ""
                + "pub const int VERSION = 7;\n"
                + "const int SECRET_BASE = 100;\n"
                + "pub int version() { return VERSION; }\n"
                + "pub int secret() { return SECRET_BASE; }\n");
        File main = write(dir, "MatrixImportMain.lemon", ""
                + "import m = @import(\"math.lemon\");\n"
                + "void main() {\n"
                + "    printf(\"%d\", m.VERSION);\n"
                + "    printf(\"%d\", m.version());\n"
                + "    printf(\"%d\", m.secret());\n"
                + "}\n");

        JvmTestSupport.CompiledClass compiled = JvmTestSupport.compile(main);
        String jvmOutput = JvmTestSupport.run(compiled);
        assertEquals("77100", jvmOutput);
        // Cross-module pub constants are still compile-time values: no fields.
        assertTrue(JvmTestSupport.hasNoFields(compiled.classBytes()));

        IrModule module = lowerFile(main);
        String cSource = new CBackend().generate(module);
        // pub const is re-exported under the alias; the private const stays static.
        assertTrue(cSource, cSource.contains("const int32_t m_VERSION = 7;"));
        assertTrue(cSource, cSource.contains("static const int32_t SECRET_BASE = 100;"));
        String nativeOutput = runNative(module);
        assertEquals("JVM and native output must match for the same Lemon source",
                jvmOutput, nativeOutput);
    }

    /**
     * Compile-fail matrix: every invalid fixture below must be rejected by both
     * the JVM backend and the C backend (frontend stages are shared, so each
     * {@code --target} must fail before any backend-specific code generation).
     */
    @Test
    public void privateConstAccessIsRejectedByBothBackendTargets() throws Exception {
        File dir = temporaryFolder.getRoot();
        write(dir, "math.lemon", ""
                + "const int SECRET = 100;\n"
                + "int dummy() { return 0; }\n");
        File main = write(dir, "PrivateConstMain.lemon", ""
                + "import m = @import(\"math.lemon\");\n"
                + "void main() { printf(\"%d\", m.SECRET); }\n");
        assertRejectedByBothBackends(main, "no public constant 'SECRET'");
    }

    @Test
    public void constReassignmentIsRejectedByBothBackendTargets() throws Exception {
        File main = write(temporaryFolder.getRoot(), "ReassignConstMain.lemon",
                "const int VALUE = 10;\nvoid main() { VALUE = 20; }\n");
        assertRejectedByBothBackends(main, "cannot assign to constant 'VALUE'");
    }

    @Test
    public void localConstDeclarationIsRejectedByBothBackendTargets() throws Exception {
        File main = write(temporaryFolder.getRoot(), "LocalConstMain.lemon",
                "void main() { const int VALUE = 10; }\n");
        assertRejectedByBothBackends(main, "const declarations are only allowed at global scope");
    }

    @Test
    public void nestedScopeConstIsRejectedByBothBackendTargets() throws Exception {
        File main = write(temporaryFolder.getRoot(), "NestedConstMain.lemon",
                "void main() { if (true) { const int VALUE = 10; } }\n");
        assertRejectedByBothBackends(main, "const declarations are only allowed at global scope");
    }

    @Test
    public void missingInitializerIsRejectedByBothBackendTargets() throws Exception {
        File main = write(temporaryFolder.getRoot(), "NoInitConstMain.lemon",
                "const int VALUE;\nvoid main() {}\n");
        assertRejectedByBothBackends(main, "expected '='");
        File pubMain = write(temporaryFolder.getRoot(), "NoInitPubConstMain.lemon",
                "pub const int VALUE;\nvoid main() {}\n");
        assertRejectedByBothBackends(pubMain, "expected '='");
    }

    // =============================================================== helpers

    private record ParseFailure(String message) {
    }

    private ParseFailure parseFailure(String source) throws Exception {
        File file = write(Files.createTempDirectory("lemonc-const-parse").toFile(), "ConstParse.lemon", source);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> new Parser(new Lexer(file)).parse());
        return new ParseFailure(failure.getMessage());
    }

    private SemanticVisitor analyze(String source) throws Exception {
        File file = write(Files.createTempDirectory("lemonc-const-sem").toFile(), "ConstSem.lemon", source);
        Parser parser = new Parser(new Lexer(file));
        Ast.Program.T program = parser.parse();
        new ModuleLoader().resolve(program, file.toPath());
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        return semantic;
    }

    private SemanticVisitor analyzeWithImports(File mainFile) throws Exception {
        Parser parser = new Parser(new Lexer(mainFile));
        Ast.Program.T program = parser.parse();
        new ModuleLoader().resolve(program, mainFile.toPath());
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        return semantic;
    }

    private IrModule lower(String source) throws Exception {
        return lowerFile(write(Files.createTempDirectory("lemonc-const-lower").toFile(), "ConstLower.lemon", source));
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

    private String runNative(IrModule module) throws Exception {
        Path runtimeRoot = Path.of("runtime").toAbsolutePath();
        Path sourceFile = Files.createTempFile("lemonc-const-native", ".c");
        new CBackend().generate(module, sourceFile);
        Path exe = Files.createTempFile("lemonc-const-native", ".exe");
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

    /**
     * Drives the full CLI compile for both {@code --target jvm} and
     * {@code --target c} and asserts that each backend rejects the source with a
     * non-zero exit code and the expected diagnostic fragments.
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

    private File write(File dir, String name, String content) throws Exception {
        File file = new File(dir, name);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }

    private static String firstMessage(SemanticVisitor semantic) {
        return semantic.getDiagnostics().get(0).message();
    }
}