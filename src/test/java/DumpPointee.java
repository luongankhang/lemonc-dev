import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.optimizer.AstOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.lexer.Lexer;
import site.ilemon.compiler.ModuleLoader;
import site.ilemon.semantic.SemanticVisitor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class DumpPointee {
    public static void main(String[] args) throws Exception {
        String code = """
                struct Container {
                    int id;
                    int items[2];
                };

                void updateContainer(struct Container* c) {
                    c->id = 999;
                    c->items[0] = 123;
                }

                void main() {
                    struct Container c;
                    c.id = 1;
                    c.items[0] = 10;
                    c.items[1] = 20;

                    struct Container* ptr;
                    ptr = &c;
                    printf("before: %d [%d]\\n", c.id, c.items[0]);
                    updateContainer(ptr);
                    printf("after: %d [%d]\\n", c.id, c.items[0]);
                }
                """;
        Path dir = Files.createTempDirectory("lemonc-dump");
        Path file = dir.resolve("PointerAliasDemo.lemon");
        Files.writeString(file, code);

        Lexer lexer = new Lexer(file.toFile());
        Parser parser = new Parser(lexer);
        var program = parser.parse();
        System.err.println("PARSED OK");

        // Check semantic first
        SemanticVisitor sv = SemanticVisitor.collecting();
        sv.visit(program);
        if (!sv.passOrNot()) {
            System.err.println("SEMANTIC ERRORS:");
            for (var d : sv.getDiagnostics()) {
                System.err.println("  " + d.code() + ": " + d.message());
            }
            return;
        }
        System.err.println("SEMANTIC OK");

        // Check module types
        new ModuleLoader().resolve(program, file);
        System.err.println("LOADED OK");

        IrModule module = new AstToIrLowerer().lower(new AstOptimizer().optimize(program));
        System.err.println("LOWERED OK");

        System.err.println("=== Structs ===");
        for (Map.Entry<String, IrModule.IrStruct> e : module.structsView().entrySet()) {
            System.err.println("  name=" + e.getKey() + " fields=" + e.getValue().fields());
        }
        System.err.println("=== Functions ===");
        for (var fn : module.functions()) {
            System.err.println("Function: " + fn.name());
            for (var block : fn.blocks()) {
                System.err.println("  Block: " + block.name());
                for (var inst : block.instructions()) {
                    System.err.println("    " + inst);
                }
            }
        }

        // Compile with LemonC to see the error
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        ByteArrayOutputStream outOut = new ByteArrayOutputStream();
        int rc = LemonC.run(new String[]{file.toString(), "--target", "jvm"},
                new PrintStream(outOut), new PrintStream(errOut));
        System.err.println("LemonC exit: " + rc);
        System.err.println("Error output: " + errOut.toString(StandardCharsets.UTF_8));
    }
}
