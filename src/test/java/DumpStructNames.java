import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.optimizer.AstOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.lexer.Lexer;
import site.ilemon.compiler.ModuleLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class DumpStructNames {
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

                    struct Buffer b2;
                    b2 = b1;
                    b2.id = 2;
                    printf("b1: %d [%d, %d]\\n", b1.id, b1.data[0], b1.data[1]);
                }
                """;
        Path dir = Files.createTempDirectory("lemonc-dump");
        Path file = dir.resolve("StructCopyDemo.lemon");
        Files.writeString(file, code);

        Lexer lexer = new Lexer(file.toFile());
        Parser parser = new Parser(lexer);
        var program = parser.parse();
        new ModuleLoader().resolve(program, file);
        IrModule module = new AstToIrLowerer().lower(new AstOptimizer().optimize(program));

        System.err.println("=== Structs in module ===");
        for (Map.Entry<String, IrModule.IrStruct> e : module.structsView().entrySet()) {
            System.err.println("  name=" + e.getKey() + " fields=" + e.getValue().fields());
        }
        System.err.println("=== Structs (fields) ===");
        for (IrModule.IrStruct s : module.structsView().values()) {
            System.err.println("Struct " + s.name() + ":");
            for (var f : s.fields()) {
                System.err.println("  " + f.name() + ": " + f.type());
            }
        }
    }
}
