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
 * Comprehensive test suite for local variable declaration placement throughout blocks.
 * Covers:
 * - Declarations after executable statements
 * - Declarations with initializers
 * - Declarations in nested blocks, if/else branches, while/for loops
 * - For loop variable declarations (for (int i = 0; ...))
 * - Interleaved declarations and statements
 * - Initializers referencing earlier variables
 * - Lexical scoping and rejection of use-before-declaration / scope leakage / duplicates
 * - Dual backend execution parity (JVM vs C/native)
 */
public class LocalVarDeclTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    // ==================== Backend execution tests ====================

    @Test
    public void testDeclAfterExecutableStatements() throws Exception {
        String code = "void main() {\n" +
                "    int x = 10;\n" +
                "    printf(\"%d\\n\", x);\n" +
                "    int y = 20;\n" +
                "    x = x + y;\n" +
                "    printf(\"%d\\n\", x);\n" +
                "}\n";
        assertBothBackendsProduce(code, "10\n30\n");
    }

    @Test
    public void testDeclInNestedBlock() throws Exception {
        String code = "void main() {\n" +
                "    int x = 1;\n" +
                "    {\n" +
                "        int y = 2;\n" +
                "        x = x + y;\n" +
                "    }\n" +
                "    printf(\"%d\\n\", x);\n" +
                "}\n";
        assertBothBackendsProduce(code, "3\n");
    }

    @Test
    public void testDeclInIfElseBranches() throws Exception {
        String code = "void main() {\n" +
                "    int cond = 1;\n" +
                "    if (cond == 1) {\n" +
                "        int branchVar = 42;\n" +
                "        printf(\"%d\\n\", branchVar);\n" +
                "    } else {\n" +
                "        int elseVar = 99;\n" +
                "        printf(\"%d\\n\", elseVar);\n" +
                "    }\n" +
                "    cond = 0;\n" +
                "    if (cond == 1) {\n" +
                "        int branchVar2 = 42;\n" +
                "        printf(\"%d\\n\", branchVar2);\n" +
                "    } else {\n" +
                "        int elseVar2 = 99;\n" +
                "        printf(\"%d\\n\", elseVar2);\n" +
                "    }\n" +
                "}\n";
        assertBothBackendsProduce(code, "42\n99\n");
    }

    @Test
    public void testForLoopInitAndBodyDeclarations() throws Exception {
        // As specified in the task requirement
        String code = "void main() {\n" +
                "    int total = 0;\n" +
                "    for (int i = 0; i < 5; i = i + 1) {\n" +
                "        int value = (i + 1) * 10;\n" +
                "        total = total + value;\n" +
                "    }\n" +
                "    int best = total;\n" +
                "    printf(\"%d\\n\", best);\n" +
                "}\n";
        assertBothBackendsProduce(code, "150\n");
    }

    @Test
    public void testWhileLoopBodyDeclarations() throws Exception {
        String code = "void main() {\n" +
                "    int i = 0;\n" +
                "    int sum = 0;\n" +
                "    while (i < 4) {\n" +
                "        int step = i * 2;\n" +
                "        sum = sum + step;\n" +
                "        i = i + 1;\n" +
                "    }\n" +
                "    printf(\"%d\\n\", sum);\n" +
                "}\n";
        assertBothBackendsProduce(code, "12\n");
    }

    @Test
    public void testDeclWithInitializerReferencingEarlierVars() throws Exception {
        String code = "void main() {\n" +
                "    int a = 5;\n" +
                "    int b = a * 2;\n" +
                "    int c = a + b;\n" +
                "    printf(\"%d\\n\", c);\n" +
                "}\n";
        assertBothBackendsProduce(code, "15\n");
    }

    @Test
    public void testMultipleInterleavedDeclsAndStatements() throws Exception {
        String code = "void main() {\n" +
                "    int a = 1;\n" +
                "    printf(\"%d\\n\", a);\n" +
                "    int b = 2;\n" +
                "    printf(\"%d\\n\", b);\n" +
                "    int c = 3;\n" +
                "    printf(\"%d\\n\", c);\n" +
                "    int d = a + b + c;\n" +
                "    printf(\"%d\\n\", d);\n" +
                "}\n";
        assertBothBackendsProduce(code, "1\n2\n3\n6\n");
    }

    @Test
    public void testLegacyTopDeclarationsContinueToWork() throws Exception {
        String code = "void main() {\n" +
                "    int a;\n" +
                "    int b;\n" +
                "    a = 10;\n" +
                "    b = 20;\n" +
                "    printf(\"%d\\n\", a + b);\n" +
                "}\n";
        assertBothBackendsProduce(code, "30\n");
    }

    @Test
    public void testSiblingScopesCanReuseVariableNames() throws Exception {
        String code = "void main() {\n" +
                "    {\n" +
                "        int x = 10;\n" +
                "        printf(\"%d\\n\", x);\n" +
                "    }\n" +
                "    {\n" +
                "        int x = 20;\n" +
                "        printf(\"%d\\n\", x);\n" +
                "    }\n" +
                "}\n";
        assertBothBackendsProduce(code, "10\n20\n");
    }

    @Test
    public void testArrayDeclarationMidBlock() throws Exception {
        String code = "void main() {\n" +
                "    printf(\"start\\n\");\n" +
                "    int arr[3];\n" +
                "    arr[0] = 7;\n" +
                "    arr[1] = 8;\n" +
                "    arr[2] = 9;\n" +
                "    printf(\"%d %d %d\\n\", arr[0], arr[1], arr[2]);\n" +
                "}\n";
        assertBothBackendsProduce(code, "start\n7 8 9\n");
    }

    @Test
    public void testPointerDeclarationMidBlock() throws Exception {
        String code = "void main() {\n" +
                "    int val = 42;\n" +
                "    printf(\"%d\\n\", val);\n" +
                "    int* ptr = &val;\n" +
                "    *ptr = 100;\n" +
                "    printf(\"%d\\n\", val);\n" +
                "}\n";
        assertBothBackendsProduce(code, "42\n100\n");
    }

    // ==================== Semantic rejection tests ====================

    @Test
    public void testUseBeforeDeclarationRejected() throws Exception {
        String code = "void main() {\n" +
                "    x = 10;\n" +
                "    int x;\n" +
                "}\n";
        List<Diagnostic> diags = analyzeSource(code);
        assertTrue("Expected semantic error", diags.stream().anyMatch(d -> DiagnosticCodes.SEM_UNKNOWN_VARIABLE.equals(d.code())));
    }

    @Test
    public void testScopeLeakageOutsideBlockRejected() throws Exception {
        String code = "void main() {\n" +
                "    {\n" +
                "        int scoped = 42;\n" +
                "    }\n" +
                "    printf(\"%d\\n\", scoped);\n" +
                "}\n";
        List<Diagnostic> diags = analyzeSource(code);
        assertTrue("Expected semantic error for scoped variable leakage",
                diags.stream().anyMatch(d -> DiagnosticCodes.SEM_UNKNOWN_VARIABLE.equals(d.code())));
    }

    @Test
    public void testForLoopInitLeakageRejected() throws Exception {
        String code = "void main() {\n" +
                "    for (int i = 0; i < 5; i = i + 1) {\n" +
                "    }\n" +
                "    printf(\"%d\\n\", i);\n" +
                "}\n";
        List<Diagnostic> diags = analyzeSource(code);
        assertTrue("Expected semantic error for loop index leakage",
                diags.stream().anyMatch(d -> DiagnosticCodes.SEM_UNKNOWN_VARIABLE.equals(d.code())));
    }

    @Test
    public void testDuplicateDeclarationInSameBlockRejected() throws Exception {
        String code = "void main() {\n" +
                "    int x = 1;\n" +
                "    int x = 2;\n" +
                "}\n";
        List<Diagnostic> diags = analyzeSource(code);
        assertTrue("Expected duplicate declaration error",
                diags.stream().anyMatch(d -> DiagnosticCodes.SEM_DUPLICATE_DECLARATION.equals(d.code())));
    }

    @Test
    public void testDuplicateDeclarationInNestedScopeRejected() throws Exception {
        String code = "void main() {\n" +
                "    int x = 1;\n" +
                "    {\n" +
                "        int x = 2;\n" +
                "    }\n" +
                "}\n";
        List<Diagnostic> diags = analyzeSource(code);
        assertTrue("Expected duplicate declaration error when shadowing",
                diags.stream().anyMatch(d -> DiagnosticCodes.SEM_DUPLICATE_DECLARATION.equals(d.code())));
    }

    // ==================== Helper methods ====================

    private void assertBothBackendsProduce(String code, String expectedOutput) throws Exception {
        Path workDir = temporaryFolder.newFolder().toPath();
        Path sourceFile = workDir.resolve("Main.lemon");
        Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

        // Compile and run JVM backend
        assertEquals("JVM compile failed for:\n" + code, 0, compile(sourceFile, "jvm"));
        String jvmOutput = runJvm("Main");
        assertEquals("JVM output mismatch", expectedOutput, jvmOutput);

        // Compile and run C backend
        assertEquals("C compile failed for:\n" + code, 0, compile(sourceFile, "c"));
        String nativeOutput = runNative(sourceFile);
        assertEquals("Native output mismatch", expectedOutput, nativeOutput);

        // Cross-backend parity
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
            // Caught when semantic analysis halts on diagnostic
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
