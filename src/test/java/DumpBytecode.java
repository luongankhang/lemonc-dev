import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.optimizer.AstOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.lexer.Lexer;
import site.ilemon.compiler.ModuleLoader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class DumpBytecode {
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

        // Parse and lower
        Lexer lexer = new Lexer(file.toFile());
        Parser parser = new Parser(lexer);
        var program = parser.parse();
        new ModuleLoader().resolve(program, file);
        IrModule module = new AstToIrLowerer().lower(new AstOptimizer().optimize(program));

        // Print IR for debug
        System.err.println("=== IR ===");
        for (var fn : module.functions()) {
            System.err.println("Function: " + fn.name() + " returns " + fn.returnType());
            for (var block : fn.blocks()) {
                System.err.println("  Block: " + block.name());
                for (var inst : block.instructions()) {
                    System.err.println("    " + inst);
                }
            }
        }
        System.err.println("=== Structs ===");
        for (var s : module.structsView().values()) {
            System.err.println("Struct " + s.name() + " fields:");
            for (var f : s.fields()) {
                System.err.println("  " + f.name() + ": " + f.type());
            }
        }

        // Emit
        JvmBackend backend = new JvmBackend();
        byte[] bytes = backend.toBytecode(module, true);
        System.err.println("=== Bytecode length: " + bytes.length + " ===");
        // Print as hex
        for (int i = 0; i < bytes.length; i++) {
            System.err.printf("%02X ", bytes[i]);
            if ((i + 1) % 16 == 0) System.err.println();
        }
        System.err.println();

        // Write class file
        Files.write(dir.resolve("StructCopyDemo.class"), bytes);
        System.out.println("Class written to: " + dir.resolve("StructCopyDemo.class"));
    }
}
