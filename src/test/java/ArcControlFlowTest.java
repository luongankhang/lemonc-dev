import org.junit.Test;
import site.ilemon.arc.MemoryOp;
import site.ilemon.arc.OwnershipAnalyzer;
import site.ilemon.arc.OwnershipFunction;
import site.ilemon.arc.OwnershipIr;
import site.ilemon.arc.RefcountSimulator;
import site.ilemon.ast.Ast;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArcControlFlowTest {

    private OwnershipIr analyzeSource(String source) throws Exception {
        File dir = Files.createTempDirectory("lemonc-arc-test").toFile();
        File file = new File(dir, "Demo.lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        try {
            Ast.Program.T program = new Parser(new Lexer(file)).parse();
            return new OwnershipAnalyzer().analyze(program);
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test
    public void testBasicAllocationAndScopeRelease() throws Exception {
        String code = "void main() { int arr[3]; arr[0] = 1; printf(\"%d\", arr[0]); }";
        OwnershipIr ir = analyzeSource(code);

        assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.ALLOC));
        assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.BOUNDS_CHECK));
        assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.RELEASE));
        assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.SCOPE_EXIT));

        RefcountSimulator simulator = new RefcountSimulator();
        simulator.verify(ir);
        assertFalse(simulator.hasErrors());
    }

    @Test
    public void testIfElseBranchBalancing() throws Exception {
        String code = "void main() {\n" +
                "    bool cond;\n" +
                "    int left[2];\n" +
                "    int right[2];\n" +
                "    cond = true;\n" +
                "    if (cond) {\n" +
                "        left[0] = 10;\n" +
                "    } else {\n" +
                "        right[0] = 20;\n" +
                "    }\n" +
                "}";
        OwnershipIr ir = analyzeSource(code);

        // Verify CFG structure
        assertEquals(1, ir.functions().size());
        OwnershipFunction mainFunc = ir.functions().get(0);
        assertTrue(mainFunc.blocks().stream().anyMatch(b -> b.name().startsWith("if.then")));
        assertTrue(mainFunc.blocks().stream().anyMatch(b -> b.name().startsWith("if.else")));
        assertTrue(mainFunc.blocks().stream().anyMatch(b -> b.name().startsWith("if.join")));

        RefcountSimulator simulator = new RefcountSimulator();
        simulator.verify(ir);
        assertFalse("Both branches should balance reference counts cleanly at join point", simulator.hasErrors());
    }

    @Test
    public void testWhileLoopOwnershipAndBackEdge() throws Exception {
        String code = "void main() {\n" +
                "    int i;\n" +
                "    int arr[5];\n" +
                "    i = 0;\n" +
                "    while (i < 5) {\n" +
                "        arr[i] = i;\n" +
                "        i = i + 1;\n" +
                "    }\n" +
                "}";
        OwnershipIr ir = analyzeSource(code);

        OwnershipFunction func = ir.functions().get(0);
        assertTrue(func.blocks().stream().anyMatch(b -> b.name().startsWith("while.cond")));
        assertTrue(func.blocks().stream().anyMatch(b -> b.name().startsWith("while.body")));
        assertTrue(func.blocks().stream().anyMatch(b -> b.name().startsWith("while.exit")));

        RefcountSimulator simulator = new RefcountSimulator();
        simulator.verify(ir);
        assertFalse("Loop carried ownership must stabilize without imbalance", simulator.hasErrors());
    }

    @Test
    public void testForLoopWithBreakAndContinue() throws Exception {
        String code = "void main() {\n" +
                "    int i;\n" +
                "    int arr[4];\n" +
                "    for (i = 0; i < 4; i = i + 1) {\n" +
                "        if (i == 1) {\n" +
                "            continue;\n" +
                "        }\n" +
                "        if (i == 3) {\n" +
                "            break;\n" +
                "        }\n" +
                "        arr[i] = i * 2;\n" +
                "    }\n" +
                "}";
        OwnershipIr ir = analyzeSource(code);

        OwnershipFunction func = ir.functions().get(0);
        assertTrue(func.blocks().stream().anyMatch(b -> b.name().startsWith("for.cond")));
        assertTrue(func.blocks().stream().anyMatch(b -> b.name().startsWith("for.update")));
        assertTrue(func.blocks().stream().anyMatch(b -> b.name().startsWith("for.exit")));

        RefcountSimulator simulator = new RefcountSimulator();
        simulator.verify(ir);
        assertFalse("Break and continue paths must cleanly exit the loop without leaking references", simulator.hasErrors());
    }

    @Test
    public void testEarlyReturnReleasesLocalsOnAllPaths() throws Exception {
        String code = "int test(int val) {\n" +
                "    int items[2];\n" +
                "    items[0] = val;\n" +
                "    if (val > 10) {\n" +
                "        return items[0];\n" +
                "    }\n" +
                "    return items[0] + 1;\n" +
                "}\n" +
                "void main() {\n" +
                "    int r;\n" +
                "    r = test(5);\n" +
                "}";
        OwnershipIr ir = analyzeSource(code);

        OwnershipFunction testFunc = ir.functions().stream()
                .filter(f -> f.name().equals("test"))
                .findFirst()
                .orElseThrow();

        // Check that RELEASE was emitted for items before the return
        assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.RELEASE && op.value().equals("items")));

        RefcountSimulator simulator = new RefcountSimulator();
        simulator.verify(ir);
        assertFalse("Early return must release local references on all return paths", simulator.hasErrors());
    }

    @Test
    public void testAssignmentRetainOnStoreAndReleaseOld() throws Exception {
        String code = "void main() {\n" +
                "    int a[2];\n" +
                "    int b[2];\n" +
                "    a[0] = 1;\n" +
                "    b[0] = 2;\n" +
                "    b = a;\n" +
                "}";
        OwnershipIr ir = analyzeSource(code);

        assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.STORE && op.value().contains("b = a")));
        assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.RETAIN && op.value().equals("a")));

        RefcountSimulator simulator = new RefcountSimulator();
        simulator.verify(ir);
        assertFalse("Assignment b = a must be reference-balanced with retain-before-release", simulator.hasErrors());
    }
}
