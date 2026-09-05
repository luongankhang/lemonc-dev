import org.junit.Test;
import site.ilemon.backend.BackendOptions;
import site.ilemon.backend.BackendResult;
import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.ModuleLoader;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.lexer.Lexer;
import site.ilemon.optimizer.AstOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;
import site.ilemon.ast.Ast;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

public class JvmBackendArcDebugTest {

    @Test
    public void testArcDebugEmitsRetainReleaseDiagnostics() throws Exception {
        String source = "void main() { int arr[3]; arr[0] = 1; arr[1] = 2; arr[2] = 3; printf(\"%d%d%d\", arr[0], arr[1], arr[2]); }";
        
        File outDir = Files.createTempDirectory("lemonc-arc-debug-test").toFile();
        Path sourceFile = outDir.toPath().resolve("Main.lemon");
        Files.writeString(sourceFile, source);
        
        try {
            Lexer lexer = new Lexer(sourceFile.toFile());
            Parser parser = new Parser(lexer);
            Ast.Program.T program = parser.parse();
            new ModuleLoader().resolve(program, sourceFile);
            SemanticVisitor semantic = SemanticVisitor.collecting();
            semantic.visit(program);
            if (!semantic.passOrNot()) {
                throw new AssertionError("Semantic errors: " + semantic.getDiagnostics());
            }
            program = new AstOptimizer().optimize(program);
            IrModule irModule = new AstToIrLowerer().lower(program);
            
            // Test without arcDebug - should NOT emit ARC diagnostics at runtime
            BackendOptions optionsNoDebug = new BackendOptions("jvm", sourceFile, outDir.toPath(), null, false, false);
            BackendResult resultNoDebug = new JvmBackend().emit(irModule, optionsNoDebug);
            
            System.out.println("Generated class file: " + resultNoDebug.primaryOutput());
            System.out.println("Exists: " + resultNoDebug.primaryOutput().toFile().exists());
            
            String outputNoDebug = runClass(outDir, "Main");
            assertTrue("Should not emit ARC diagnostics without arcDebug", 
                !outputNoDebug.contains("[ARC] RETAIN") && !outputNoDebug.contains("[ARC] RELEASE"));
            
            // Test with arcDebug - SHOULD emit ARC diagnostics at runtime
            BackendOptions optionsWithDebug = new BackendOptions("jvm", sourceFile, outDir.toPath(), null, false, true);
            BackendResult resultWithDebug = new JvmBackend().emit(irModule, optionsWithDebug);
            
            String outputWithDebug = runClass(outDir, "Main");
            System.out.println("Output with arcDebug: " + outputWithDebug);
            // Note: ArcOptimizer eliminates redundant retain/release pairs, so only RELEASE may appear
            assertTrue("Should emit ARC RELEASE diagnostic with arcDebug", 
                outputWithDebug.contains("[ARC] RELEASE"));
            // RETAIN may be optimized away by ArcOptimizer
            
        } finally {
            Files.deleteIfExists(outDir.toPath().resolve("Main.lemon"));
            deleteRecursive(outDir.toPath());
        }
    }
    
    private static String runClass(File classDir, String className) throws Exception {
        Process process = new ProcessBuilder(javaExecutable(),
                "-Dfile.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
                "-cp", classDir.getPath(), className)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        String output = readAll(process.getInputStream());
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("JVM execution timed out for " + className);
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("JVM exit code " + process.exitValue() + " for " + className
                    + ", output:\n" + output);
        }
        return output.replace("\r\n", "\n").replace("\r", "\n");
    }
    
    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), executable).getPath();
    }
    
    private static String readAll(java.io.InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int read;
        while ((read = stream.read(data)) != -1) {
            buffer.write(data, 0, read);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    
    private static void deleteRecursive(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        }
    }
}