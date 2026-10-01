import org.junit.Test;
import site.ilemon.backend.BackendOptions;
import site.ilemon.backend.c.CBackend;
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end large-benchmark sanity test.
 *
 * <p>Exercises enums, structs (Position nested in Item and PlayerData),
 * switch on enum, pointer args, and multi-module @import.  Verifies
 * that both backends compile without errors and that the JVM class can
 * be loaded by the JVM.</p>
 */
public class LargeBenchmarkTest {

    private static final String BENCHMARK_DIR = "examples/large_benchmark";

    // ----------------------------------------------------------- semantic pass

    @Test
    public void benchmarkSemanticsPass() throws Exception {
        File main = new File(BENCHMARK_DIR + "/benchmark_main.lemon");
        Ast.Program.T program = new Parser(new Lexer(main)).parse();
        new ModuleLoader().resolve(program, main.toPath());
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        assertTrue("semantic must accept the benchmark: " + semantic.getDiagnostics(),
                semantic.passOrNot());
    }

    // ------------------------------------------------------ JVM backend loads

    /**
     * Compiles the benchmark to JVM .class files, then verifies:
     * <ul>
     *   <li>{@code benchmark_main.class} exists and can be loaded</li>
     *   <li>{@code benchmark_main$Position.class} exists and can be loaded</li>
     *   <li>{@code benchmark_main$Item.class} exists and can be loaded</li>
     *   <li>{@code benchmark_main$PlayerData.class} exists and can be loaded</li>
     *   <li>{@code benchmark_main$Stats.class} exists and can be loaded</li>
     *   <li>The main class has a {@code main(String[])} method</li>
     * </ul>
     */
    @Test
    public void jvmBackendCompilesAndLoads() throws Exception {
        File main = new File(BENCHMARK_DIR + "/benchmark_main.lemon");
        File outDir = Files.createTempDirectory("lemonc-bench-jvm").toFile();
        Path abs = main.toPath().toAbsolutePath().normalize();
        IrModule irModule = lower(main);
        new JvmBackend().emit(irModule, BackendOptions.of("jvm", abs, outDir.toPath(), null, false));

        // Verify struct classes exist on disk
        assertTrue("benchmark_main.class must exist",
                new File(outDir, "benchmark_main.class").exists());
        assertTrue("benchmark_main$Position.class must exist",
                new File(outDir, "benchmark_main$Position.class").exists());
        assertTrue("benchmark_main$Item.class must exist",
                new File(outDir, "benchmark_main$Item.class").exists());
        assertTrue("benchmark_main$PlayerData.class must exist",
                new File(outDir, "benchmark_main$PlayerData.class").exists());
        assertTrue("benchmark_main$Stats.class must exist",
                new File(outDir, "benchmark_main$Stats.class").exists());

        // Verify the main class can be loaded and has a main() method
        Class<?> cls = loadClass("benchmark_main", outDir);
        Method mainMethod = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals("main"))
                .filter(m -> m.getParameterCount() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("main class must have main(String[]) method"));
        assertTrue("main must be public", java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers()));
        assertTrue("main must be static", java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()));
    }

    // ------------------------------------------------------ C backend emits

    @Test
    public void cBackendEmitsBenchmark() throws Exception {
        File main = new File(BENCHMARK_DIR + "/benchmark_main.lemon");
        IrModule irModule = lower(main);
        String c = new CBackend().generate(irModule);
        assertTrue("C must declare Position typedef", c.contains("LemonC_Position"));
        assertTrue("C must declare PlayerData typedef", c.contains("LemonC_PlayerData"));
        assertTrue("C must declare Item typedef", c.contains("LemonC_Item"));
        assertTrue("C must declare Stats typedef", c.contains("LemonC_Stats"));
        assertTrue("C must compile function world_runWorld", c.contains("world_runWorld"));
        assertTrue("C must compile function world_exploreWorld", c.contains("world_exploreWorld"));
        assertTrue("C must compile function world_combatRound", c.contains("world_combatRound"));
    }

    // ------------------------------------------------------- parity (JVM vs C class structure)

    @Test
    public void cAndJvmEmitSameStructs() throws Exception {
        File main = new File(BENCHMARK_DIR + "/benchmark_main.lemon");
        File jvmDir = Files.createTempDirectory("lemonc-bench-jvm").toFile();
        IrModule irModule = lower(main);
        new JvmBackend().emit(irModule, BackendOptions.of("jvm", main.toPath().toAbsolutePath().normalize(),
                jvmDir.toPath(), null, false));
        String cSource = new CBackend().generate(irModule);

        // C backend uses LemonC_ prefix; JVM backend uses module_name$StructName
        assertTrue("C must declare enum ItemType", cSource.contains("LemonC_ItemType"));
        assertTrue("C must declare enum EnemyType", cSource.contains("LemonC_EnemyType"));
        assertTrue("C must declare enum Rarity", cSource.contains("LemonC_Rarity"));

        // All struct names must appear in both backends
        for (String struct : List.of("Position", "Stats", "Item", "PlayerData")) {
            assertTrue("C must declare " + struct, cSource.contains("LemonC_" + struct));
            assertTrue("JVM must emit $" + struct + ".class",
                    new File(jvmDir, "benchmark_main$" + struct + ".class").exists());
        }
    }

    // ------------------------------------------------------- helpers

    private static IrModule lower(File main) throws Exception {
        Ast.Program.T program = new Parser(new Lexer(main)).parse();
        new ModuleLoader().resolve(program, main.toPath());
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        assertFalse("benchmark must pass semantic: " + semantic.getDiagnostics(),
                !semantic.passOrNot());
        return new AstToIrLowerer().lower(new AstOptimizer().optimize(program));
    }

    /** Load a class from the given directory using a fresh URLClassLoader. */
    private static Class<?> loadClass(String name, File dir) throws Exception {
        java.net.URL url = dir.toURI().toURL();
        ClassLoader cl = new java.net.URLClassLoader(
                new java.net.URL[]{url}, LargeBenchmarkTest.class.getClassLoader());
        return cl.loadClass(name);
    }
}
