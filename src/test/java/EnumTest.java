import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.ast.Ast;
import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;
import site.ilemon.compiler.ModuleLoader;
import site.ilemon.diagnostic.DiagnosticCodes;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrType;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Comprehensive test suite for LemonC enum support.
 * Covers:
 * - Parsing: enum declarations, explicit values, negative values, trailing commas, optional semicolons.
 * - Semantic analysis: strict nominal type checking, duplicate detection, scope rules.
 * - IR lowering: enum types and constants lowered to shared LemonIR.
 * - Backend code generation & execution: byte-for-byte output parity between C and JVM backends.
 * - Cross-module scoping: pub enum vs private enum visibility.
 * - Negative diagnostics: type mismatches, arithmetic rejection, private enum leaks, duplicate members.
 */
public class EnumTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int compile(Path source, String target, ByteArrayOutputStream errors, String... extraArgs) {
        String[] args;
        if (extraArgs.length > 0) {
            args = new String[3 + extraArgs.length];
            args[0] = source.toString();
            args[1] = "--target";
            args[2] = target;
            System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        } else {
            args = new String[]{source.toString(), "--target", target};
        }
        return LemonC.run(args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
    }

    private Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String compileAndRunJvm(Path main, String... extraArgs) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = compile(main, "jvm", errors, extraArgs);
        assertEquals("jvm compile failed: " + errors, 0, exitCode);
        return JvmTestSupport.run(baseName(main), new File(JvmBackend.DEFAULT_OUTPUT_DIR));
    }

    private String compileAndRunNative(Path main, String... extraArgs) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = compile(main, "c", errors, extraArgs);
        assertEquals("native compile failed: " + errors, 0, exitCode);
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

    private SemanticVisitor runSemantic(String source) throws Exception {
        Path dir = temporaryFolder.newFolder("enum-semantic").toPath();
        Path file = write(dir, "Test.lemon", source);
        Ast.Program.T program = new Parser(new Lexer(file.toFile())).parse();
        new ModuleLoader().resolve(program, file);
        SemanticVisitor visitor = SemanticVisitor.collecting();
        visitor.visit(program);
        return visitor;
    }

    private boolean isRejected(String source) {
        try {
            SemanticVisitor visitor = runSemantic(source);
            return !visitor.passOrNot();
        } catch (Exception e) {
            return true;
        }
    }

    // ============================================ Positive Unit / End-to-End Tests

    @Test
    public void testEnumAstParsingAndIrLowering() throws Exception {
        String source = """
                enum Color {
                    RED,
                    GREEN = 10,
                    BLUE,
                    CUSTOM = -5,
                };
                void main() {
                    enum Color c = Color.GREEN;
                    enum Color c2 = BLUE;
                    printf("%d %d\\n", c, c2);
                }
                """;
        Path dir = temporaryFolder.newFolder("enum-ast").toPath();
        Path file = write(dir, "EnumAst.lemon", source);
        Ast.Program.T program = new Parser(new Lexer(file.toFile())).parse();
        Ast.MainClass.MainClassSingle main =
                (Ast.MainClass.MainClassSingle) ((Ast.Program.ProgramSingle) program).getMainClass();
        assertEquals(1, main.getEnums().size());
        Ast.EnumDecl decl = main.getEnums().get(0);
        assertEquals("Color", decl.getName());
        assertEquals(4, decl.getMembers().size());
        assertEquals("RED", decl.getMembers().get(0).getName());
        assertEquals(0, decl.getMembers().get(0).getValue());
        assertEquals("GREEN", decl.getMembers().get(1).getName());
        assertEquals(10, decl.getMembers().get(1).getValue());
        assertEquals("BLUE", decl.getMembers().get(2).getName());
        assertEquals(11, decl.getMembers().get(2).getValue());
        assertEquals("CUSTOM", decl.getMembers().get(3).getName());
        assertEquals(-5, decl.getMembers().get(3).getValue());

        new ModuleLoader().resolve(program, file);
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        assertTrue("Semantic validation should pass", semantic.passOrNot());

        IrModule irModule = new AstToIrLowerer().lower(program);
        assertTrue("IrModule should contain Color enum", irModule.enumsView().containsKey("Color"));
        assertEquals(4, irModule.irEnum("Color").members().size());
    }

    @Test
    public void testEnumExecutionParity_C_and_JVM() throws Exception {
        String source = """
                enum Status {
                    IDLE,
                    RUNNING = 5,
                    COMPLETED,
                    FAILED = -1
                }

                enum Status next_status(enum Status current) {
                    if (current == Status.IDLE) {
                        return Status.RUNNING;
                    }
                    if (current == Status.RUNNING) {
                        return Status.COMPLETED;
                    }
                    return Status.FAILED;
                }

                void main() {
                    enum Status s = Status.IDLE;
                    printf("Initial: %d\\n", s);
                    s = next_status(s);
                    printf("Next 1: %d\\n", s);
                    s = next_status(s);
                    printf("Next 2: %d\\n", s);
                    if (s == Status.COMPLETED) {
                        printf("Completed successfully\\n");
                    }
                    if (s != Status.FAILED) {
                        printf("Not failed\\n");
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("enum-exec").toPath();
        Path file = write(dir, "EnumExec.lemon", source);

        String jvmOut = compileAndRunJvm(file);
        String nativeOut = compileAndRunNative(file);

        String expected = """
                Initial: 0
                Next 1: 5
                Next 2: 6
                Completed successfully
                Not failed
                """.replace("\r\n", "\n");

        assertEquals(expected, jvmOut);
        assertEquals(expected, nativeOut);
    }

    @Test
    public void testEnumInStructAndPointer() throws Exception {
        String source = """
                enum Priority {
                    LOW = 1,
                    MEDIUM = 2,
                    HIGH = 3
                }

                struct Task {
                    int id;
                    enum Priority priority;
                }

                void bump_priority(struct Task* t) {
                    if (t->priority == Priority.LOW) {
                        t->priority = Priority.MEDIUM;
                    } else if (t->priority == Priority.MEDIUM) {
                        t->priority = Priority.HIGH;
                    }
                }

                void main() {
                    struct Task t;
                    t.id = 101;
                    t.priority = Priority.LOW;
                    printf("Task id: %d, priority: %d\\n", t.id, t.priority);
                    bump_priority(&t);
                    printf("After bump 1: %d\\n", t.priority);
                    bump_priority(&t);
                    printf("After bump 2: %d\\n", t.priority);
                }
                """;
        Path dir = temporaryFolder.newFolder("enum-struct").toPath();
        Path file = write(dir, "EnumStruct.lemon", source);

        String jvmOut = compileAndRunJvm(file);
        String nativeOut = compileAndRunNative(file);

        String expected = """
                Task id: 101, priority: 1
                After bump 1: 2
                After bump 2: 3
                """.replace("\r\n", "\n");

        assertEquals(expected, jvmOut);
        assertEquals(expected, nativeOut);
    }

    @Test
    public void testCrossModulePubEnum() throws Exception {
        Path dir = temporaryFolder.newFolder("cross-enum").toPath();
        Path colorMod = write(dir, "colors.lemon", """
                pub enum Color {
                    RED = 1,
                    GREEN = 2,
                    BLUE = 3
                }

                pub enum Color default_color() {
                    return Color.GREEN;
                }

                pub int color_code(enum Color c) {
                    if (c == Color.RED) {
                        return 100;
                    }
                    if (c == Color.GREEN) {
                        return 200;
                    }
                    return 300;
                }
                """);

        Path mainFile = write(dir, "main.lemon", """
                import col = @import("colors.lemon");

                void main() {
                    enum col.Color c = col.default_color();
                    printf("Default color: %d\\n", c);
                    printf("Code: %d\\n", col.color_code(c));
                    c = col.Color.BLUE;
                    printf("Blue: %d\\n", c);
                    printf("Blue code: %d\\n", col.color_code(c));
                }
                """);

        String jvmOut = compileAndRunJvm(mainFile);
        String nativeOut = compileAndRunNative(mainFile);

        String expected = """
                Default color: 2
                Code: 200
                Blue: 3
                Blue code: 300
                """.replace("\r\n", "\n");

        assertEquals(expected, jvmOut);
        assertEquals(expected, nativeOut);
    }

    // ============================================ Negative Tests & Diagnostics

    @Test
    public void rejectsDuplicateEnumDeclaration() throws Exception {
        String source = """
                enum Color { RED, GREEN }
                enum Color { BLUE, YELLOW }
                void main() {}
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasDuplicate = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.SEM_DUPLICATE_DECLARATION.equals(d.code())
                        && d.message().contains("duplicate enum"));
        assertTrue("Should report SEM_DUPLICATE_DECLARATION for duplicate enum", hasDuplicate);
    }

    @Test
    public void rejectsDuplicateEnumMember() throws Exception {
        String source = """
                enum Color { RED, GREEN, RED }
                void main() {}
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasDuplicate = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.SEM_DUPLICATE_DECLARATION.equals(d.code())
                        && d.message().contains("duplicate member"));
        assertTrue("Should report SEM_DUPLICATE_DECLARATION for duplicate enum member", hasDuplicate);
    }

    @Test
    public void rejectsAssigningIntToEnum() throws Exception {
        String source = """
                enum Color { RED, GREEN }
                void main() {
                    enum Color c = 1;
                }
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasTypeError = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.TYPE_ASSIGNMENT.equals(d.code()));
        assertTrue("Assigning int to enum should fail with TYPE_ASSIGNMENT", hasTypeError);
    }

    @Test
    public void rejectsAssigningEnumToInt() throws Exception {
        String source = """
                enum Color { RED, GREEN }
                void main() {
                    int x = Color.RED;
                }
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasTypeError = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.TYPE_ASSIGNMENT.equals(d.code()));
        assertTrue("Assigning enum to int should fail with TYPE_ASSIGNMENT", hasTypeError);
    }

    @Test
    public void rejectsComparingDifferentEnums() throws Exception {
        String source = """
                enum Color { RED, GREEN }
                enum Shape { CIRCLE, SQUARE }
                void main() {
                    enum Color c = Color.RED;
                    enum Shape s = Shape.CIRCLE;
                    if (c == s) {
                        printf("Match\\n");
                    }
                }
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasOpError = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.TYPE_OPERATOR.equals(d.code()));
        assertTrue("Comparing different enums should fail with TYPE_OPERATOR", hasOpError);
    }

    @Test
    public void rejectsComparingEnumWithInt() throws Exception {
        String source = """
                enum Color { RED, GREEN }
                void main() {
                    enum Color c = Color.RED;
                    if (c == 0) {
                        printf("Zero\\n");
                    }
                }
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasOpError = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.TYPE_OPERATOR.equals(d.code()));
        assertTrue("Comparing enum with int should fail with TYPE_OPERATOR", hasOpError);
    }

    @Test
    public void rejectsArithmeticOnEnum() throws Exception {
        String source = """
                enum Color { RED, GREEN }
                void main() {
                    enum Color c = Color.RED;
                    enum Color c2 = c + 1;
                }
                """;
        assertTrue("Arithmetic on enum must be rejected", isRejected(source));
    }

    @Test
    public void rejectsReassigningEnumMember() throws Exception {
        String source = """
                enum Color { RED, GREEN }
                void main() {
                    RED = 10;
                }
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasImmutableError = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.SEM_CONST_IMMUTABLE.equals(d.code()));
        assertTrue("Reassigning bare enum member must fail with SEM_CONST_IMMUTABLE", hasImmutableError);
    }

    @Test
    public void rejectsReassigningQualifiedEnumMember() throws Exception {
        String source = """
                enum Color { RED, GREEN }
                void main() {
                    Color.RED = 10;
                }
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasImmutableError = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.SEM_CONST_IMMUTABLE.equals(d.code()));
        assertTrue("Reassigning Color.RED must fail with SEM_CONST_IMMUTABLE", hasImmutableError);
    }

    @Test
    public void rejectsPrivateEnumAccessAcrossModules() throws Exception {
        Path dir = temporaryFolder.newFolder("private-enum").toPath();
        write(dir, "secret.lemon", """
                enum SecretCode {
                    ALPHA,
                    BETA
                }
                void dummy() {}
                """);
        Path mainFile = write(dir, "main.lemon", """
                import sec = @import("secret.lemon");
                void main() {
                    enum sec.SecretCode s;
                }
                """);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = compile(mainFile, "jvm", errors);
        assertFalse("Accessing private enum across modules must fail", exitCode == 0);
        assertTrue("Error message should mention private enum, but was: " + errors.toString(StandardCharsets.UTF_8),
                errors.toString(StandardCharsets.UTF_8).contains("private enum"));
    }

    @Test
    public void rejectsPublicFunctionExposingPrivateEnum() throws Exception {
        String source = """
                enum Secret { A, B }
                pub enum Secret get_secret() {
                    return Secret.A;
                }
                void main() {}
                """;
        SemanticVisitor visitor = runSemantic(source);
        assertFalse(visitor.passOrNot());
        boolean hasScopeError = visitor.getDiagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.SEM_INVALID_SCOPE.equals(d.code())
                        && d.message().contains("private enum"));
        assertTrue("Exposing private enum in public return type must fail with SEM_INVALID_SCOPE", hasScopeError);
    }
}
