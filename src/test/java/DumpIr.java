import site.ilemon.compiler.LemonC;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.ArcOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.lexer.Lexer;
import site.ilemon.compiler.ModuleLoader;
import site.ilemon.semantic.SemanticVisitor;
import site.ilemon.optimizer.AstOptimizer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DumpIr {
    public static void main(String[] args) throws Exception {
        String code = """
                struct Buffer {
                    int id;
                    int data[2];
                };

                void main() {
                    struct Buffer b1;
                    b1.id = 1;
                    b1.data[0] = 11;
                    b1.data[1] = 22;
                }
                """;
        Path dir = Files.createTempDirectory("lemonc-dump");
        Path file = dir.resolve("Test.lemon");
        Files.writeString(file, code);

        Lexer lexer = new Lexer(file.toFile());
        Parser parser = new Parser(lexer);
        var program = parser.parse();
        SemanticVisitor sv = SemanticVisitor.collecting();
        sv.visit(program);
        new ModuleLoader().resolve(program, file);
        var optimized = new AstOptimizer().optimize(program);
        IrModule module = new AstToIrLowerer().lower(optimized);
        new ArcOptimizer().optimize(module);

        System.err.println("=== IR ===");
        for (var fn : module.functions()) {
            System.err.println("Function: " + fn.name());
            for (var block : fn.blocks()) {
                System.err.println("  Block: " + block.name());
                for (var inst : block.instructions()) {
                    System.err.println("    " + inst);
                }
            }
        }
    }
}
