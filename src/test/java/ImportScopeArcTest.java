import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.compiler.LemonC;
import site.ilemon.semantic.ImportSymbol;
import site.ilemon.semantic.ScopeManager;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImportScopeArcTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int compile(Path source, String... flags) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        String[] args = new String[flags.length + 1];
        args[0] = source.toString();
        System.arraycopy(flags, 0, args, 1, flags.length);
        return LemonC.run(args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
    }

    @Test
    public void localImportDoesNotLeakOutOfNestedBlock() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("math.lemon"), "pub int add(int a, int b) { return a + b; }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "void main() { { import math = @import(\"math.lemon\"); math.add(1, 2); } math.add(3, 4); }\n", StandardCharsets.UTF_8);
        assertEquals(1, compile(main, "--target", "jvm"));
    }

    @Test
    public void localImportsAreFunctionScoped() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("math.lemon"), "pub int add(int a, int b) { return a + b; }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "void foo() { import math = @import(\"math.lemon\"); math.add(1, 2); } void bar() { math.add(3, 4); } void main() { }\n", StandardCharsets.UTF_8);
        assertEquals(1, compile(main, "--target", "jvm"));
    }

    @Test
    public void compileTimeImportProducesNoArcMemoryOperation() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("math.lemon"), "pub int add(int a, int b) { return a + b; }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "void main() { import math = @import(\"math.lemon\"); math.add(1, 2); }\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LemonC.run(new String[]{main.toString(), "--arc-analysis"}, new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));
        String arc = output.toString(StandardCharsets.UTF_8);
        assertTrue(!arc.contains("ALLOC math") && !arc.contains("RETAIN math") && !arc.contains("RELEASE math") && !arc.contains("free(math)"));
    }

    @Test
    public void localBindingsHaveDistinctIdentityForOneModule() {
        ScopeManager scopes = new ScopeManager();
        Path module = Path.of("math.lemon").toAbsolutePath().normalize();
        ImportSymbol first = scopes.declareImport("math", module);
        scopes.enterScope();
        ImportSymbol second = scopes.declareImport("math", module);
        assertTrue(first != second);
        assertTrue(!first.isRuntimeStorage() && !second.isRuntimeStorage());
        scopes.exitScope();
        assertTrue(scopes.resolveImport("math") == first);
    }
}
