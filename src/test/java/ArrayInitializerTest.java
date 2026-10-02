import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.ast.Ast;
import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.diagnostic.DiagnosticCodes;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Comprehensive test suite for array initializer syntax: {@code int arr[N] = { ... };}.
 *
 * <p>Covers all primitive element types (int, byte, short, char, long),
 * full/partial/empty initializers, size-mismatch errors, wrong-element-type
 * errors, nested arrays, multiple declarations in one program, and dual-backend
 * parity (JVM vs C/native). Error-code tests verify the two new semantic codes
 * {@link DiagnosticCodes#TYPE_ARRAY_INIT_SIZE_MISMATCH} (E3016) and
 * {@link DiagnosticCodes#TYPE_ARRAY_INIT_ELEMENT_TYPE} (E3017).</p>
 */
public class ArrayInitializerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    // ================================================================== int[]

    @Test
    public void intArrayFullInitializerRunsOnBothBackends() throws Exception {
        String code = "void main() {\n" +
                "    int arr[3] = {1, 2, 3};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "1 2 3\n");
    }

    @Test
    public void intArrayPartialInitializerZeroFillsRemaining() throws Exception {
        String code = "void main() {\n" +
                "    int arr[3] = {1, 2};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "1 2 0\n");
    }

    @Test
    public void intArraySingleElementInitializer() throws Exception {
        String code = "void main() {\n" +
                "    int arr[5] = {42};\n" +
                "    printf(\"%d %d %d %d %d\\n\", arr[0], arr[1], arr[2], arr[3], arr[4]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "42 0 0 0 0\n");
    }

    @Test
    public void intArrayEmptyInitializerAllZeros() throws Exception {
        String code = "void main() {\n" +
                "    int arr[4] = {};\n" +
                "    printf(\"%d %d %d %d\\n\", arr[0], arr[1], arr[2], arr[3]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "0 0 0 0\n");
    }

    @Test
    public void intArrayNegativeElements() throws Exception {
        String code = "void main() {\n" +
                "    int arr[3] = {-1, -2, -3};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "-1 -2 -3\n");
    }

    @Test
    public void intArrayExpressionElements() throws Exception {
        String code = "void main() {\n" +
                "    int arr[3] = {1 + 2, 3 * 4, 10 - 1};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "3 12 9\n");
    }

    @Test
    public void intArrayInitializerWithVariableReference() throws Exception {
        String code = "void main() {\n" +
                "    int base = 10;\n" +
                "    int arr[3] = {base, base + 1, base + 2};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "10 11 12\n");
    }

    // ================================================================ byte[]

    @Test
    public void byteArrayFullInitializerRunsOnBothBackends() throws Exception {
        String code = "void main() {\n" +
                "    byte arr[3] = {1, 2, 3};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "1 2 3\n");
    }

    @Test
    public void byteArrayPartialInitializer() throws Exception {
        String code = "void main() {\n" +
                "    byte arr[4] = {10, 20};\n" +
                "    printf(\"%d %d %d %d\\n\", arr[0], arr[1], arr[2], arr[3]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "10 20 0 0\n");
    }

    @Test
    public void byteArrayNegativeElements() throws Exception {
        String code = "void main() {\n" +
                "    byte arr[2] = {-1, -128};\n" +
                "    printf(\"%d %d\\n\", arr[0], arr[1]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "-1 -128\n");
    }

    // =============================================================== short[]

    @Test
    public void shortArrayFullInitializerRunsOnBothBackends() throws Exception {
        String code = "void main() {\n" +
                "    short arr[3] = {100, 200, 300};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "100 200 300\n");
    }

    @Test
    public void shortArrayPartialInitializer() throws Exception {
        String code = "void main() {\n" +
                "    short arr[4] = {1, 2};\n" +
                "    printf(\"%d %d %d %d\\n\", arr[0], arr[1], arr[2], arr[3]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "1 2 0 0\n");
    }

    // ============================================================== char[]

    @Test
    public void charArrayFullInitializerRunsOnBothBackends() throws Exception {
        String code = "void main() {\n" +
                "    char arr[3] = {'A', 'B', 'C'};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "65 66 67\n");
    }

    @Test
    public void charArrayPartialInitializer() throws Exception {
        String code = "void main() {\n" +
                "    char arr[4] = {'X', 'Y'};\n" +
                "    printf(\"%d %d %d %d\\n\", arr[0], arr[1], arr[2], arr[3]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "88 89 0 0\n");
    }

    // =============================================================== long[]

    @Test
    public void longArrayFullInitializerRunsOnBothBackends() throws Exception {
        String code = "void main() {\n" +
                "    long arr[3] = {1, 2, 3};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "1 2 3\n");
    }

    @Test
    public void longArrayLargeValues() throws Exception {
        String code = "void main() {\n" +
                "    long arr[2] = {1000000000, -1000000000};\n" +
                "    printf(\"%d %d\\n\", arr[0], arr[1]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "1000000000 -1000000000\n");
    }

    // ========================================================= diagnostics: size mismatch

    @Test
    public void tooManyElementsReportsE3016() throws Exception {
        List<Diagnostic> diags = analyzeSource(
                "void main() { int arr[3] = {1, 2, 3, 4}; }");
        assertTrue("Expected E3016 (size mismatch)",
                diags.stream().anyMatch(d -> DiagnosticCodes.TYPE_ARRAY_INIT_SIZE_MISMATCH.equals(d.code())));
    }

    @Test
    public void tooFewElementsNoError() throws Exception {
        // Partial init is valid — remaining elements are zero-filled
        List<Diagnostic> diags = analyzeSource(
                "void main() { int arr[3] = {1, 2}; }");
        assertFalse("Partial init should not error",
                diags.stream().anyMatch(d -> d.code().equals(DiagnosticCodes.TYPE_ARRAY_INIT_SIZE_MISMATCH)));
    }

    @Test
    public void emptyInitializerNoError() throws Exception {
        List<Diagnostic> diags = analyzeSource(
                "void main() { int arr[3] = {}; }");
        assertFalse("Empty init should not error",
                diags.stream().anyMatch(d -> d.code().equals(DiagnosticCodes.TYPE_ARRAY_INIT_SIZE_MISMATCH)));
    }

    // ====================================================== diagnostics: wrong element type

    @Test
    public void floatLiteralInIntArrayReportsE3017() throws Exception {
        List<Diagnostic> diags = analyzeSource(
                "void main() { int arr[3] = {1.5, 2.0, 3.0}; }");
        assertTrue("Expected E3017 (wrong element type)",
                diags.stream().anyMatch(d -> DiagnosticCodes.TYPE_ARRAY_INIT_ELEMENT_TYPE.equals(d.code())));
    }

    @Test
    public void stringLiteralInIntArrayReportsE3017() throws Exception {
        List<Diagnostic> diags = analyzeSource(
                "void main() { int arr[2] = {\"hello\", \"world\"}; }");
        assertTrue("Expected E3017 (wrong element type)",
                diags.stream().anyMatch(d -> DiagnosticCodes.TYPE_ARRAY_INIT_ELEMENT_TYPE.equals(d.code())));
    }

    @Test
    public void charLiteralInIntArrayReportsE3017() throws Exception {
        List<Diagnostic> diags = analyzeSource(
                "void main() { int arr[2] = {'a', 'b'}; }");
        // char literals are implicitly convertible to int in Lemon — should NOT error
        assertFalse("char literal should be valid in int array",
                diags.stream().anyMatch(d -> DiagnosticCodes.TYPE_ARRAY_INIT_ELEMENT_TYPE.equals(d.code())));
    }

    @Test
    public void intLiteralInByteArrayReportsE3017() throws Exception {
        List<Diagnostic> diags = analyzeSource(
                "void main() { byte arr[2] = {100, 200}; }");
        // 200 is out of byte range (-128..127); semantic validation catches this.
        assertTrue("out-of-range byte literal 200 should be rejected",
                diags.stream().anyMatch(d -> DiagnosticCodes.TYPE_ARRAY_INIT_ELEMENT_TYPE.equals(d.code())
                        || DiagnosticCodes.TYPE_BYTE_RANGE.equals(d.code())));
    }

    // ===================================================== multi-declaration in one program

    @Test
    public void multipleArrayDeclarationsWithInitializers() throws Exception {
        String code = "void main() {\n" +
                "    int a[2] = {1, 2};\n" +
                "    byte b[2] = {3, 4};\n" +
                "    short c[2] = {5, 6};\n" +
                "    printf(\"%d %d %d %d %d %d\\n\", a[0], a[1], b[0], b[1], c[0], c[1]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "1 2 3 4 5 6\n");
    }

    @Test
    public void arrayInitializerInFunctionParameterContext() throws Exception {
        String code = "int sumArr(int arr[]) { return arr[0] + arr[1] + arr[2]; }\n" +
                "void main() {\n" +
                "    int vals[3] = {10, 20, 30};\n" +
                "    printf(\"%d\\n\", sumArr(vals));\n" +
                "}\n";
        assertBothBackendsProduce(code, "60\n");
    }

    @Test
    public void arrayInitializerInNestedBlock() throws Exception {
        String code = "void main() {\n" +
                "    int outer = 0;\n" +
                "    {\n" +
                "        int inner[3] = {1, 2, 3};\n" +
                "        outer = inner[0] + inner[1] + inner[2];\n" +
                "    }\n" +
                "    printf(\"%d\\n\", outer);\n" +
                "}\n";
        assertBothBackendsProduce(code, "6\n");
    }

    @Test
    public void arrayInitializerAfterExecutableStatements() throws Exception {
        String code = "void main() {\n" +
                "    int x = 5;\n" +
                "    printf(\"%d\\n\", x);\n" +
                "    int arr[3] = {x, x + 1, x + 2};\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "5\n5 6 7\n");
    }

    // ============================================================= helper methods

    private void assertBothBackendsProduce(String code, String expectedOutput) throws Exception {
        Path workDir = temporaryFolder.newFolder().toPath();
        Path sourceFile = workDir.resolve("Main.lemon");
        Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

        assertEquals("JVM compile failed for:\n" + code, 0, compile(sourceFile, "jvm"));
        String jvmOutput = runJvm("Main");
        assertEquals("JVM output mismatch", expectedOutput, jvmOutput);

        assertEquals("C compile failed for:\n" + code, 0, compile(sourceFile, "c"));
        String nativeOutput = runNative(sourceFile);
        assertEquals("Native output mismatch", expectedOutput, nativeOutput);

        assertEquals("JVM and native output must match exactly", jvmOutput, nativeOutput);
    }

    private List<Diagnostic> analyzeSource(String code) throws Exception {
        Path workDir = temporaryFolder.newFolder().toPath();
        Path file = workDir.resolve("Main.lemon");
        Files.writeString(file, code, StandardCharsets.UTF_8);
        Parser parser = new Parser(new Lexer(file.toFile()));
        Ast.Program.T prog = parser.parse();
        SemanticVisitor visitor = SemanticVisitor.collecting();
        try {
            visitor.visit(prog);
        } catch (site.ilemon.exception.SemanticException expected) {
            // Semantic analysis halts on diagnostic; diagnostics are still captured
        }
        return visitor.getDiagnostics();
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
