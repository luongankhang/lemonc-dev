import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;
import site.ilemon.backend.BackendOptions;
import site.ilemon.backend.BackendResult;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.optimizer.AstOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.lexer.Lexer;
import site.ilemon.compiler.ModuleLoader;
import site.ilemon.semantic.SemanticVisitor;
import site.ilemon.ir.ArcOptimizer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class DumpStructCopy {
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

        // Use LemonC.run to compile exactly like the test does
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        ByteArrayOutputStream outOut = new ByteArrayOutputStream();
        int rc = LemonC.run(new String[]{file.toString(), "--target", "jvm", "--arc", "--arc-verify"},
                new PrintStream(outOut), new PrintStream(errOut));
        System.err.println("LemonC exit: " + rc);
        System.err.println("Error output: " + errOut.toString(StandardCharsets.UTF_8));
        System.err.println("Output: " + outOut.toString(StandardCharsets.UTF_8));

        // Also run through the parser pipeline directly to see IR
        Lexer lexer = new Lexer(file.toFile());
        Parser parser = new Parser(lexer);
        var program = parser.parse();
        SemanticVisitor sv = SemanticVisitor.collecting();
        sv.visit(program);
        if (!sv.passOrNot()) {
            System.err.println("SEMANTIC ERRORS:");
            for (var d : sv.getDiagnostics()) {
                System.err.println("  " + d.code() + ": " + d.message());
            }
            return;
        }
        new ModuleLoader().resolve(program, file);
        IrModule module = new AstToIrLowerer().lower(new AstOptimizer().optimize(program));
        new ArcOptimizer().optimize(module);

        System.err.println("=== IR after ARC optimize ===");
        for (var fn : module.functions()) {
            System.err.println("Function: " + fn.name());
            for (var block : fn.blocks()) {
                System.err.println("  Block: " + block.name());
                for (var inst : block.instructions()) {
                    System.err.println("    " + inst);
                }
            }
        }

        // Also generate to DEFAULT_OUTPUT_DIR like the test does
        JvmBackend backend2 = new JvmBackend();
        java.io.File defaultDir = new java.io.File(JvmBackend.DEFAULT_OUTPUT_DIR);
        defaultDir.mkdirs();
        java.io.File classFile = defaultDir.toPath().resolve("StructCopyDemo.class").toFile();
        // Use emit instead of toBytecode to write all files
        BackendOptions opts = new BackendOptions(
                "jvm", file.toAbsolutePath(), defaultDir.toPath(), null, false, true);
        BackendResult result = backend2.emit(module, opts);
        System.err.println("Primary output: " + result.primaryOutput());
        for (var f : result.outputs()) {
            System.err.println("  Output: " + f);
        }

        // Try to run it
        try {
            Process p = new ProcessBuilder("java.exe", "-cp", dir.toString(), "StructCopyDemo")
                    .redirectErrorStream(true).start();
            Thread.sleep(3000);
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.err.println("JVM output: " + out);
            System.err.println("JVM exit: " + p.exitValue());
        } catch (Exception e) {
            System.err.println("JVM error: " + e.getMessage());
        }
    }
}
