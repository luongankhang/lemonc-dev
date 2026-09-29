import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.compiler.LemonC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration and parity test suite for C-like syntax operators:
 * - Prefix/postfix ++ and -- (locals, array elements, pointer dereferences, struct fields)
 * - Compound assignment: +=, -=, *=, /=, %=
 * - Unary operators: +, -, !, ~
 * - Ternary operator: condition ? trueExpr : falseExpr
 * - Precedence and single-evaluation of compound assignment LHS.
 */
public class OperatorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testOperatorShowcaseJvmAndCParity() throws Exception {
        Path showcase = Path.of("examples", "operator", "OperatorShowcase.lemon");
        assertTrue("source file should exist: " + showcase, Files.isRegularFile(showcase));

        Path workDir = temporaryFolder.getRoot().toPath();
        Path copied = workDir.resolve("OperatorShowcase.lemon");
        Files.copy(showcase, copied);

        assertEquals("jvm compile failed", 0, compile(copied, "jvm"));
        String jvmOutput = runJvm("OperatorShowcase");

        assertEquals("c compile failed", 0, compile(copied, "c"));
        String nativeOutput = runNative(copied);

        String expected = String.join("\n",
                "postA=5, a=6",
                "preA=7, a=7",
                "postDecA=7, a=6",
                "preDecA=5, a=5",
                "after standalone inc: a=6",
                "after standalone dec: a=5",
                "a += 10 -> 15",
                "a -= 3 -> 12",
                "a *= 2 -> 24",
                "a /= 4 -> 6",
                "a mod_assign 4 -> 2",
                "arr[0]++ -> 101",
                "++arr[0] -> 102",
                "arr[0]-- -> 101",
                "--arr[0] -> 100",
                "arr[0] += 50 -> 150",
                "arr[1]=225, idx=2",
                "(*p)++ -> 41",
                "++(*p) -> 42",
                "(*p)-- -> 41",
                "--(*p) -> 40",
                "*p += 8 -> 48",
                "*p -= 3 -> 45",
                "pt.x=11, pt.y=21",
                "pPt->x=16, pPt->y=42",
                "+num=15, -num=-15",
                "!flag is true",
                "~0=-1, ~10=-11",
                "maxVal=999, minVal=2",
                "grade=2",
                "callRes=31, arg1=11, arg2=21",
                "for-loop sum=10"
        ) + "\n";

        assertEquals("JVM output must match expected", expected, jvmOutput);
        assertEquals("Native output must match expected", expected, nativeOutput);
        assertEquals("JVM and C output must be byte-for-byte identical", jvmOutput, nativeOutput);
    }

    @Test
    public void testCompoundAssignmentLhsEvaluatedOnce() throws Exception {
        String source = ""
                + "void main() {\n"
                + "    int arr[5];\n"
                + "    arr[0] = 10;\n"
                + "    arr[1] = 20;\n"
                + "    int i = 0;\n"
                + "    arr[i++] += 5;\n"
                + "    printf(\"arr[0]=%d, i=%d\\n\", arr[0], i);\n"
                + "    arr[i++] *= 3;\n"
                + "    printf(\"arr[1]=%d, i=%d\\n\", arr[1], i);\n"
                + "}\n";
        Path file = write("CompoundOnce.lemon", source);
        assertEquals(0, compile(file, "jvm"));
        String jvm = runJvm("CompoundOnce");
        assertEquals(0, compile(file, "c"));
        String nat = runNative(file);
        String expected = "arr[0]=15, i=1\narr[1]=60, i=2\n";
        assertEquals(expected, jvm);
        assertEquals(expected, nat);
    }

    @Test
    public void testPrefixAndPostfixPrecedenceInExpressions() throws Exception {
        String source = ""
                + "int calc(int x, int y) {\n"
                + "    return x * 10 + y;\n"
                + "}\n"
                + "void main() {\n"
                + "    int a = 1;\n"
                + "    int b = 2;\n"
                + "    int res = calc(a++, ++b);\n"
                + "    printf(\"res=%d, a=%d, b=%d\\n\", res, a, b);\n"
                + "    int c = 10;\n"
                + "    int d = c++ + ++c;\n"
                + "    printf(\"d=%d, c=%d\\n\", d, c);\n"
                + "}\n";
        Path file = write("PrePostExpr.lemon", source);
        assertEquals(0, compile(file, "jvm"));
        String jvm = runJvm("PrePostExpr");
        assertEquals(0, compile(file, "c"));
        String nat = runNative(file);
        assertEquals(jvm, nat);
    }

    @Test
    public void testTernaryAndBitwiseNot() throws Exception {
        String source = ""
                + "void main() {\n"
                + "    int x = 5;\n"
                + "    int y = (x > 3) ? 100 : 200;\n"
                + "    int z = (x < 3) ? 100 : 200;\n"
                + "    int nested = (x == 5) ? ((y == 100) ? 1 : 2) : 3;\n"
                + "    int notX = ~x;\n"
                + "    printf(\"y=%d, z=%d, nested=%d, notX=%d\\n\", y, z, nested, notX);\n"
                + "}\n";
        Path file = write("TernaryBitwise.lemon", source);
        assertEquals(0, compile(file, "jvm"));
        String jvm = runJvm("TernaryBitwise");
        assertEquals(0, compile(file, "c"));
        String nat = runNative(file);
        String expected = "y=100, z=200, nested=1, notX=-6\n";
        assertEquals(expected, jvm);
        assertEquals(expected, nat);
    }

    private Path write(String name, String content) throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private int compile(Path source, String target) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = LemonC.run(new String[]{source.toString(), "--target", target},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        if (code != 0) {
            System.err.println(errors.toString(StandardCharsets.UTF_8));
        }
        return code;
    }

    private String runJvm(String className) throws Exception {
        return JvmTestSupport.run(className, new File(JvmBackend.DEFAULT_OUTPUT_DIR));
    }

    private String runNative(Path main) throws Exception {
        String name = main.getFileName().toString();
        String baseName = name.substring(0, name.length() - ".lemon".length());
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exe = main.getParent().resolve(baseName + (isWindows ? ".exe" : ""));
        assertTrue("Executable does not exist: " + exe, Files.isRegularFile(exe));

        Process process = new ProcessBuilder(exe.toAbsolutePath().toString()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("native process exited with non-zero status", 0, process.waitFor());
        return output.replace("\r\n", "\n").replace("\r", "\n");
    }
}