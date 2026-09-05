import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.compiler.LemonC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModuleSystemTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int compile(Path source, String target, ByteArrayOutputStream errors) {
        return LemonC.run(new String[]{source.toString(), "--target", target},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
    }

    @Test
    public void publicFunctionCanBeImportedAndExecuted() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("math.lemon"), "pub int add(int a, int b) { return a + b; }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "import math = @import(\"math.lemon\");\nvoid main() { printf(\"%d\\n\", math.add(2, 3)); }\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(0, compile(main, "c", errors));
    }

    @Test
    public void privateFunctionIsNotExported() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("math.lemon"), "int secret() { return 42; }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "import math = @import(\"math.lemon\");\nvoid main() { printf(\"%d\\n\", math.secret()); }\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(1, compile(main, "jvm", errors));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("undefined function"));
    }

    @Test
    public void missingModuleHasDiagnostic() throws Exception {
        Path main = temporaryFolder.getRoot().toPath().resolve("main.lemon");
        Files.writeString(main, "import missing = @import(\"missing.lemon\");\nvoid main() { }\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(1, compile(main, "jvm", errors));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("module not found"));
    }

    @Test
    public void circularModuleDependencyHasDiagnostic() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Files.writeString(dir.resolve("a.lemon"), "import b = @import(\"b.lemon\");\nvoid a() { }\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.lemon"), "import a = @import(\"a.lemon\");\nvoid b() { }\n", StandardCharsets.UTF_8);
        Path main = dir.resolve("main.lemon");
        Files.writeString(main, "import a = @import(\"a.lemon\");\nvoid main() { }\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(1, compile(main, "jvm", errors));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("circular module dependency"));
    }
}
