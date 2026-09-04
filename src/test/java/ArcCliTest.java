import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.compiler.LemonC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ArcCliTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File createTestProgram() throws Exception {
        Path sourceFile = temporaryFolder.getRoot().toPath().resolve("ArcDemo.lemon");
        String code = "void main() { int arr[2]; arr[0] = 42; printf(\"%d\", arr[0]); }";
        Files.writeString(sourceFile, code, StandardCharsets.UTF_8);
        return sourceFile.toFile();
    }

    @Test
    public void testCliWithArcFlag() throws Exception {
        File file = createTestProgram();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath(), "--arc"}, new PrintStream(out), new PrintStream(err));
        assertEquals("Expected exit code 0 with --arc, errors: " + err, 0, exitCode);
    }

    @Test
    public void testCliWithArcVerify() throws Exception {
        File file = createTestProgram();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath(), "--arc-verify"}, new PrintStream(out), new PrintStream(err));
        assertEquals("Expected exit code 0 with --arc-verify, errors: " + err, 0, exitCode);
    }

    @Test
    public void testCliWithArcAnalysis() throws Exception {
        File file = createTestProgram();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath(), "--arc-analysis"}, new PrintStream(out), new PrintStream(err));
        assertEquals("Expected exit code 0 with --arc-analysis, errors: " + err, 0, exitCode);
        String output = out.toString();
        assertTrue("Output should contain == ARC == header", output.contains("== ARC =="));
        assertTrue("Output should contain ALLOC", output.contains("ALLOC"));
        assertTrue("Output should contain RELEASE", output.contains("RELEASE"));
    }

    @Test
    public void testCliWithArcDebug() throws Exception {
        File file = createTestProgram();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath(), "--arc-debug"}, new PrintStream(out), new PrintStream(err));
        assertEquals("Expected exit code 0 with --arc-debug, errors: " + err, 0, exitCode);
        String output = out.toString();
        assertTrue("Debug output should contain Function info", output.contains("Function: main"));
        assertTrue("Debug output should contain Block info", output.contains("Block [entry]:"));
    }

    @Test
    public void testCliWithDumpArcBackwardCompatibility() throws Exception {
        File file = createTestProgram();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath(), "--dump-arc"}, new PrintStream(out), new PrintStream(err));
        assertEquals("Expected exit code 0 with --dump-arc, errors: " + err, 0, exitCode);
        String output = out.toString();
        assertTrue("Output should contain == ARC ==", output.contains("== ARC =="));
    }
}
