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
import site.ilemon.ir.IrInstruction;
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
 * Comprehensive test suite for LemonC switch/case/default support.
 * Covers:
 * - Parsing: switch, case, default, fallthrough, nested switch, switch in loop.
 * - Semantic analysis: constant case labels (int literal / enum member / named
 *   constant), duplicate case, duplicate default, break outside loop/switch,
 *   subject type checks, case-scoped variables.
 * - IR lowering: switch lowered to shared opcodes (CMP/COND_BRANCH/BRANCH);
 *   no backend-specific IR; break targets switch_exit.
 * - C/JVM execution parity: identical program output on both backends.
 * - Negative diagnostics: non-constant case, duplicate case/default, wrong
 *   subject type, break outside loop/switch.
 */
public class SwitchTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int compile(Path source, String target, ByteArrayOutputStream errors, String... extraArgs) {
        String[] args;
        if (extraArgs.length > 0) {
            args = new String[3 + extraArgs.length];
            args[0] = source.toString();
            args[1] = "--target";
            args[2] = target;
            System.arraycopy(extraArgs, 0, args, 3, args.length - extraArgs.length);
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
        Path dir = temporaryFolder.newFolder("switch-semantic").toPath();
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

    // ============================================ Parser / IR unit tests

    @Test
    public void testSwitchParsingAndIrLowering() throws Exception {
        String source = """
                void main() {
                    int x = 2;
                    switch (x) {
                        case 1: printf("one\\n"); break;
                        case 2: printf("two\\n"); break;
                        default: printf("other\\n");
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-ast").toPath();
        Path file = write(dir, "SwitchAst.lemon", source);
        Ast.Program.T program = new Parser(new Lexer(file.toFile())).parse();
        Ast.MainClass.MainClassSingle main =
                (Ast.MainClass.MainClassSingle) ((Ast.Program.ProgramSingle) program).getMainClass();
        Ast.Stmt.Switch switchStmt = (Ast.Stmt.Switch) ((Ast.Method.MethodSingle) main.getMethods().get(0)).getStms().get(1);
        assertEquals(3, switchStmt.getClauses().size());
        assertFalse(switchStmt.getClauses().get(0).isDefault());
        assertEquals("1", String.valueOf(((Ast.Expr.Number) switchStmt.getClauses().get(0).getLabel()).getValue()));
        assertTrue(switchStmt.getClauses().get(2).isDefault());
        // Each case body holds its statements (printf + break = 2).
        assertEquals(2, switchStmt.getClauses().get(0).getBody().size());
        assertEquals("2", String.valueOf(((Ast.Expr.Number) switchStmt.getClauses().get(1).getLabel()).getValue()));

        new ModuleLoader().resolve(program, file);
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        assertTrue("Semantic validation should pass", semantic.passOrNot());

        IrModule irModule = new AstToIrLowerer().lower(program);
        // Switch lowers to shared opcodes only: BRANCH/COND_BRANCH/CMP.
        boolean hasBranch = false;
        boolean hasCondBranch = false;
        boolean hasGotoSwitchExit = false;
        for (var function : irModule.functions()) {
            for (var block : function.blocks()) {
                for (IrInstruction inst : block.instructions()) {
                    if (inst.op() == IrInstruction.Op.BRANCH) hasBranch = true;
                    if (inst.op() == IrInstruction.Op.COND_BRANCH) hasCondBranch = true;
                    if (inst.target() != null && inst.target().startsWith("switch_exit_")) hasGotoSwitchExit = true;
                }
            }
        }
        assertTrue(hasBranch);
        assertTrue(hasCondBranch);
        assertTrue(hasGotoSwitchExit);
    }

    // ============================================ Positive end-to-end tests

    @Test
    public void testIntegerSwitchWithBreak_C_and_JVM() throws Exception {
        String source = """
                void main() {
                    int x = 2;
                    switch (x) {
                        case 1: printf("one\\n"); break;
                        case 2: printf("two\\n"); break;
                        default: printf("other\\n");
                    }
                    x = 9;
                    switch (x) {
                        case 1: printf("one\\n"); break;
                        case 2: printf("two\\n"); break;
                        default: printf("other\\n");
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-int").toPath();
        Path file = write(dir, "SwitchInt.lemon", source);
        String expected = "two\nother\n";
        assertEquals(expected, compileAndRunJvm(file));
        assertEquals(expected, compileAndRunNative(file));
    }

    @Test
    public void testEnumSwitch_C_and_JVM() throws Exception {
        String source = """
                enum Color {
                    RED,
                    GREEN = 10,
                    BLUE
                }

                void main() {
                    enum Color c = Color.GREEN;
                    switch (c) {
                        case Color.RED: printf("red\\n"); break;
                        case Color.GREEN: printf("green\\n"); break;
                        default: printf("unknown color\\n");
                    }
                    c = Color.BLUE;
                    switch (c) {
                        case Color.RED: printf("red\\n"); break;
                        case Color.GREEN: printf("green\\n"); break;
                        default: printf("unknown color\\n");
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-enum").toPath();
        Path file = write(dir, "SwitchEnum.lemon", source);
        String expected = "green\nunknown color\n";
        assertEquals(expected, compileAndRunJvm(file));
        assertEquals(expected, compileAndRunNative(file));
    }

    @Test
    public void testFallthrough_C_and_JVM() throws Exception {
        String source = """
                void main() {
                    int x = 1;
                    switch (x) {
                        case 1: printf("one\\n");
                        case 2: printf("two\\n");
                        default: printf("other\\n");
                    }
                    printf("---\\n");
                    switch (x) {
                        case 1: printf("last-case-only\\n");
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-fall").toPath();
        Path file = write(dir, "SwitchFall.lemon", source);
        String expected = "one\ntwo\nother\n---\nlast-case-only\n";
        assertEquals(expected, compileAndRunJvm(file));
        assertEquals(expected, compileAndRunNative(file));
    }

    @Test
    public void testSwitchInLoop_C_and_JVM() throws Exception {
        String source = """
                void main() {
                    int i = 0;
                    while (i < 3) {
                        switch (i) {
                            case 0: printf("zero\\n"); break;
                            case 1: printf("one\\n"); break;
                            default: printf("two\\n");
                        }
                        i = i + 1;
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-loop").toPath();
        Path file = write(dir, "SwitchLoop.lemon", source);
        String expected = "zero\none\ntwo\n";
        assertEquals(expected, compileAndRunJvm(file));
        assertEquals(expected, compileAndRunNative(file));
    }

    @Test
    public void testNestedSwitch_C_and_JVM() throws Exception {
        String source = """
                enum Op {
                    ADD_OP,
                    SUB_OP
                }

                int apply(enum Op kind, int a, int b) {
                    switch (kind) {
                        case Op.ADD_OP:
                            switch (b) {
                                case 0: printf("  b zero\\n"); break;
                                default: printf("  b nonzero\\n");
                            }
                            return a + b;
                        case Op.SUB_OP: return a - b;
                    }
                    return 0;
                }

                void main() {
                    printf("r1=%d\\n", apply(Op.ADD_OP, 5, 3));
                    printf("r2=%d\\n", apply(Op.SUB_OP, 5, 3));
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-nested").toPath();
        Path file = write(dir, "SwitchNested.lemon", source);
        // apply(1,5,3): inner switch on b=3 matches no case, falls to default
        // ("b nonzero"), then returns 5+3=8. apply(2,5,3) returns 5-3=2.
        String expected = "  b nonzero\nr1=8\nr2=2\n";
        assertEquals(expected, compileAndRunJvm(file));
        assertEquals(expected, compileAndRunNative(file));
    }

    @Test
    public void testBreakInSwitchOnlyExitsSwitch() throws Exception {
        String source = """
                void main() {
                    int i = 0;
                    while (i < 2) {
                        switch (i) {
                            case 0: printf("a\\n"); break;
                            default: printf("b\\n"); break;
                        }
                        printf("after-switch\\n");
                        i = i + 1;
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-break").toPath();
        Path file = write(dir, "SwitchBreak.lemon", source);
        String expected = "a\nafter-switch\nb\nafter-switch\n";
        assertEquals(expected, compileAndRunJvm(file));
        assertEquals(expected, compileAndRunNative(file));
    }

    @Test
    public void testSwitchWithNamedConstCase() throws Exception {
        String source = """
                const int LIMIT = 3;

                void main() {
                    int x = 3;
                    switch (x) {
                        case LIMIT: printf("limit\\n"); break;
                        default: printf("under\\n");
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-const").toPath();
        Path file = write(dir, "SwitchConst.lemon", source);
        String expected = "limit\n";
        assertEquals(expected, compileAndRunJvm(file));
        assertEquals(expected, compileAndRunNative(file));
    }

    @Test
    public void testCaseLocalScope() throws Exception {
        String source = """
                void main() {
                    int x = 1;
                    switch (x) {
                        case 1:
                            int local = 10;
                            printf("case1 %d\\n", local);
                            break;
                        default: printf("other\\n");
                    }
                    switch (x) {
                        case 1:
                            int local = 20;
                            printf("case1b %d\\n", local);
                            break;
                        default: printf("other\\n");
                    }
                }
                """;
        Path dir = temporaryFolder.newFolder("switch-scope").toPath();
        Path file = write(dir, "SwitchScope.lemon", source);
        String expected = "case1 10\ncase1b 20\n";
        assertEquals(expected, compileAndRunJvm(file));
        assertEquals(expected, compileAndRunNative(file));
    }

    // ============================================ Negative diagnostics

    @Test
    public void testDuplicateCaseRejected() {
        assertTrue(isRejected("""
                void main() {
                    int x = 1;
                    switch (x) {
                        case 1: printf("a\\n"); break;
                        case 1: printf("b\\n"); break;
                    }
                }
                """));
    }

    @Test
    public void testDuplicateEnumCaseRejected() {
        assertTrue(isRejected("""
                enum Color { RED, GREEN }
                void main() {
                    enum Color c = Color.RED;
                    switch (c) {
                        case Color.RED: printf("a\\n"); break;
                        case RED: printf("b\\n"); break;
                    }
                }
                """));
    }

    @Test
    public void testDuplicateDefaultRejected() {
        assertTrue(isRejected("""
                void main() {
                    int x = 1;
                    switch (x) {
                        case 1: printf("a\\n"); break;
                        default: printf("b\\n");
                        default: printf("c\\n");
                    }
                }
                """));
    }

    @Test
    public void testNonConstantCaseRejected() {
        assertTrue(isRejected("""
                void main() {
                    int x = 1;
                    int y = 2;
                    switch (x) {
                        case y: printf("a\\n"); break;
                    }
                }
                """));
    }

    @Test
    public void testWrongSubjectTypeRejected() {
        assertTrue(isRejected("""
                void main() {
                    float f = 1.5;
                    switch (f) {
                        case 1: printf("a\\n"); break;
                    }
                }
                """));
    }

    @Test
    public void testEnumCaseOnIntSubjectRejected() {
        assertTrue(isRejected("""
                enum Color { RED, GREEN }
                void main() {
                    int x = 1;
                    switch (x) {
                        case Color.RED: printf("a\\n"); break;
                    }
                }
                """));
    }

    @Test
    public void testBreakOutsideLoopAndSwitchRejected() {
        assertTrue(isRejected("""
                void main() {
                    break;
                }
                """));
    }

    @Test
    public void testContinueInsideSwitchOutsideLoopRejected() {
        // continue targets loops only; inside a switch with no enclosing loop
        // it must be rejected.
        assertTrue(isRejected("""
                void main() {
                    int x = 1;
                    switch (x) {
                        case 1: continue; break;
                    }
                }
                """));
    }

    @Test
    public void testBreakStillLegalInLoop() throws Exception {
        SemanticVisitor visitor = runSemantic("""
                void main() {
                    int i = 0;
                    while (i < 3) {
                        break;
                    }
                }
                """);
        assertTrue("break inside a loop must remain legal", visitor.passOrNot());
    }
}
