import org.junit.Test;
import site.ilemon.arc.MemoryOp;
import site.ilemon.arc.OwnershipAnalyzer;
import site.ilemon.arc.OwnershipIr;
import site.ilemon.arc.RefcountSimulator;
import site.ilemon.ast.Ast;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class ArcOwnershipTest {
    @Test
    public void annotatesArrayAllocationBoundsAndScopeRelease() throws Exception {
        File dir = Files.createTempDirectory("lemonc-arc").toFile();
        File file = new File(dir, "ArcExample.lemon");
        Files.writeString(file.toPath(), "class ArcExample { void main() { int values[2]; values[0] = 1; } }\n", StandardCharsets.UTF_8);
        try {
            Ast.Program.T program = new Parser(new Lexer(file)).parse();
            OwnershipIr ir = new OwnershipAnalyzer().analyze(program);
            assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.ALLOC));
            assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.BOUNDS_CHECK));
            assertTrue(ir.operations().stream().anyMatch(op -> op.kind() == MemoryOp.Kind.RELEASE));
            new RefcountSimulator().verify(ir);
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }
}
