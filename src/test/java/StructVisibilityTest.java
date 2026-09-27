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
 * Tests for pub struct and module visibility in LemonC.
 * Covers:
 * - same-module access (private struct and private field)
 * - cross-module pub struct usage
 * - private struct access rejection
 * - private field access rejection
 * - pub struct with pointers (*, ->, &)
 * - pub struct with functions (parameters and return types)
 * - pub struct with ARC pipeline
 * - public signatures exposing private structs rejection
 */
public class StructVisibilityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int compile(Path source, String target, ByteArrayOutputStream errors, String... extraArgs) {
        String[] args;
        if (extraArgs.length > 0) {
            args = new String[3 + extraArgs.length];
            args[0] = source.toString();
            args[1] = "--target";
            args[2] = target;
            System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        } else {
            args = new String[]{source.toString(), "--target", target};
        }
        return LemonC.run(args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
    }

    private Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String compileAndRunJvm(Path main, String... extraArgs) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = compile(main, "jvm", errors, extraArgs);
        assertEquals("jvm compile failed: " + errors, 0, exitCode);
        return JvmTestSupport.run(baseName(main), new File(JvmBackend.DEFAULT_OUTPUT_DIR));
    }

    private String compileAndRunNative(Path main, String... extraArgs) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = compile(main, "c", errors, extraArgs);
        assertEquals("native compile failed: " + errors, 0, exitCode);
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exe = main.getParent().resolve(baseName(main) + (isWindows ? ".exe" : ""));
        assertTrue("Executable does not exist: " + exe, Files.isRegularFile(exe));

        Process process = new ProcessBuilder(exe.toAbsolutePath().toString()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("native process exited with non-zero status", 0, process.waitFor());
        return output.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".lemon".length());
    }

    // ============================================ Same-module access

    @Test
    public void testSameModuleAccessToPrivateStructAndFields() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Path main = write(dir, "SameModuleMain.lemon",
                "struct SecretBox {\n"
                + "    int secretId;\n"
                + "    int secretValue;\n"
                + "}\n"
                + "void modifyBox(struct SecretBox* b) {\n"
                + "    b->secretValue = b->secretValue + 10;\n"
                + "}\n"
                + "void main() {\n"
                + "    struct SecretBox box;\n"
                + "    box.secretId = 1;\n"
                + "    box.secretValue = 42;\n"
                + "    modifyBox(&box);\n"
                + "    printf(\"%d %d\\n\", box.secretId, box.secretValue);\n"
                + "}\n");

        String jvmOut = compileAndRunJvm(main);
        String nativeOut = compileAndRunNative(main);
        assertEquals("1 52\n", jvmOut);
        assertEquals(jvmOut, nativeOut);
    }

    // ============================================ Cross-module pub struct

    @Test
    public void testCrossModulePubStruct() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "geo.lemon",
                "pub struct Point {\n"
                + "    int x;\n"
                + "    int y;\n"
                + "}\n"
                + "pub struct Point createPoint(int x, int y) {\n"
                + "    struct Point p;\n"
                + "    p.x = x;\n"
                + "    p.y = y;\n"
                + "    return p;\n"
                + "}\n");

        Path main = write(dir, "GeoMain.lemon",
                "import geo = @import(\"geo.lemon\");\n"
                + "void main() {\n"
                + "    struct Point p = geo.createPoint(15, 25);\n"
                + "    printf(\"%d %d\\n\", p.x, p.y);\n"
                + "}\n");

        String jvmOut = compileAndRunJvm(main);
        String nativeOut = compileAndRunNative(main);
        assertEquals("15 25\n", jvmOut);
        assertEquals(jvmOut, nativeOut);
    }

    // ============================================ Private struct rejection

    @Test
    public void testPrivateStructRejection_bareName() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "secrets.lemon",
                "struct PrivateVault {\n"
                + "    int secretCode;\n"
                + "}\n"
                + "pub void vaultHelper() {}\n");

        Path main = write(dir, "MainRejection.lemon",
                "import secrets = @import(\"secrets.lemon\");\n"
                + "void main() {\n"
                + "    struct PrivateVault vault;\n"
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject private struct: " + errors, 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected E2005 or private struct error, got: " + errStr,
                    errStr.contains("E2005") || errStr.contains("private struct"));
        }
    }

    @Test
    public void testPrivateStructRejection_qualifiedName() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "secrets.lemon",
                "struct PrivateVault {\n"
                + "    int secretCode;\n"
                + "}\n"
                + "pub void vaultHelper() {}\n");

        Path main = write(dir, "MainQualifiedRejection.lemon",
                "import secrets = @import(\"secrets.lemon\");\n"
                + "void main() {\n"
                + "    struct secrets.PrivateVault vault;\n"
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject private struct: " + errors, 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected E2005 or private struct error, got: " + errStr,
                    errStr.contains("E2005") || errStr.contains("private struct"));
        }
    }

    // ============================================ Private field rejection

    @Test
    public void testPrivateFieldRejection() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "auth.lemon",
                "pub struct UserProfile {\n"
                + "    pub int userId;\n"
                + "    int hashedToken;\n"
                + "}\n"
                + "pub struct UserProfile makeUser(int id, int token) {\n"
                + "    struct UserProfile u;\n"
                + "    u.userId = id;\n"
                + "    u.hashedToken = token;\n"
                + "    return u;\n"
                + "}\n");

        Path main = write(dir, "AuthMain.lemon",
                "import auth = @import(\"auth.lemon\");\n"
                + "void main() {\n"
                + "    struct UserProfile u = auth.makeUser(1001, 777);\n"
                + "    printf(\"%d\\n\", u.userId);\n"
                + "    printf(\"%d\\n\", u.hashedToken);\n"
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject private field access: " + errors, 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected private field error, got: " + errStr,
                    errStr.contains("private field") || errStr.contains("E2005"));
        }
    }

    // ============================================ pub struct + pointer (*, ->, &)

    @Test
    public void testCrossModulePubStructPointer() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "vec.lemon",
                "pub struct Vector2D {\n"
                + "    int x;\n"
                + "    int y;\n"
                + "}\n"
                + "pub void scale(struct Vector2D* v, int factor) {\n"
                + "    v->x = v->x * factor;\n"
                + "    v->y = v->y * factor;\n"
                + "}\n");

        Path main = write(dir, "VecMain.lemon",
                "import vec = @import(\"vec.lemon\");\n"
                + "void main() {\n"
                + "    struct Vector2D v;\n"
                + "    v.x = 3;\n"
                + "    v.y = 4;\n"
                + "    vec.scale(&v, 5);\n"
                + "    printf(\"%d %d\\n\", v.x, v.y);\n"
                + "}\n");

        String jvmOut = compileAndRunJvm(main);
        String nativeOut = compileAndRunNative(main);
        assertEquals("15 20\n", jvmOut);
        assertEquals(jvmOut, nativeOut);
    }

    // ============================================ pub struct + function

    @Test
    public void testCrossModulePubStructFunctionChaining() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "shapes.lemon",
                "pub struct Rect {\n"
                + "    int width;\n"
                + "    int height;\n"
                + "}\n"
                + "pub struct Rect makeRect(int w, int h) {\n"
                + "    struct Rect r;\n"
                + "    r.width = w;\n"
                + "    r.height = h;\n"
                + "    return r;\n"
                + "}\n"
                + "pub int area(struct Rect r) {\n"
                + "    return r.width * r.height;\n"
                + "}\n");

        Path main = write(dir, "ShapesMain.lemon",
                "import shapes = @import(\"shapes.lemon\");\n"
                + "struct Rect doubleRect(struct Rect r) {\n"
                + "    struct Rect doubled;\n"
                + "    doubled.width = r.width * 2;\n"
                + "    doubled.height = r.height * 2;\n"
                + "    return doubled;\n"
                + "}\n"
                + "void main() {\n"
                + "    struct Rect r1 = shapes.makeRect(3, 4);\n"
                + "    struct Rect r2 = doubleRect(r1);\n"
                + "    printf(\"%d %d\\n\", shapes.area(r1), shapes.area(r2));\n"
                + "}\n");

        String jvmOut = compileAndRunJvm(main);
        String nativeOut = compileAndRunNative(main);
        assertEquals("12 48\n", jvmOut);
        assertEquals(jvmOut, nativeOut);
    }

    // ============================================ pub struct + ARC

    @Test
    public void testCrossModulePubStructWithArc() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        write(dir, "dataset.lemon",
                "pub struct Item {\n"
                + "    int id;\n"
                + "    int val;\n"
                + "}\n"
                + "pub struct Item createItem(int id, int val) {\n"
                + "    struct Item item;\n"
                + "    item.id = id;\n"
                + "    item.val = val;\n"
                + "    return item;\n"
                + "}\n");

        Path main = write(dir, "ArcStructMain.lemon",
                "import dataset = @import(\"dataset.lemon\");\n"
                + "void main() {\n"
                + "    struct Item it1 = dataset.createItem(1, 100);\n"
                + "    struct Item it2 = dataset.createItem(2, 200);\n"
                + "    int values[2];\n"
                + "    values[0] = it1.val;\n"
                + "    values[1] = it2.val;\n"
                + "    printf(\"%d %d %d\\n\", it1.id, it2.id, values[0] + values[1]);\n"
                + "}\n");

        String jvmOut = compileAndRunJvm(main, "--arc", "--arc-verify");
        String nativeOut = compileAndRunNative(main, "--arc", "--arc-verify");
        assertEquals("1 2 300\n", jvmOut);
        assertEquals(jvmOut, nativeOut);
    }

    // ============================================ Public signature exposing private struct

    @Test
    public void testPublicFunctionExposingPrivateStructInReturn_rejected() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Path file = write(dir, "InvalidSigReturn.lemon",
                "struct PrivateType {\n"
                + "    int x;\n"
                + "}\n"
                + "pub struct PrivateType leak() {\n"
                + "    struct PrivateType p;\n"
                + "    p.x = 1;\n"
                + "    return p;\n"
                + "}\n"
                + "void main() {}\n");

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = compile(file, "jvm", errors);
        assertEquals(1, exitCode);
        String errStr = errors.toString(StandardCharsets.UTF_8);
        assertTrue("Expected error on private struct in public return type: " + errStr,
                errStr.contains("cannot expose private struct in return type"));
    }

    @Test
    public void testPublicFunctionExposingPrivateStructInParam_rejected() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Path file = write(dir, "InvalidSigParam.lemon",
                "struct PrivateType {\n"
                + "    int x;\n"
                + "}\n"
                + "pub void leak(struct PrivateType p) {\n"
                + "}\n"
                + "void main() {}\n");

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = compile(file, "jvm", errors);
        assertEquals(1, exitCode);
        String errStr = errors.toString(StandardCharsets.UTF_8);
        assertTrue("Expected error on private struct in public parameter: " + errStr,
                errStr.contains("cannot expose private struct in parameter"));
    }

    @Test
    public void testPublicStructExposingPrivateStructInPublicField_rejected() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        Path file = write(dir, "InvalidStructField.lemon",
                "struct PrivateType {\n"
                + "    int x;\n"
                + "}\n"
                + "pub struct PublicHolder {\n"
                + "    pub struct PrivateType inner;\n"
                + "}\n"
                + "void main() {}\n");

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = compile(file, "jvm", errors);
        assertEquals(1, exitCode);
        String errStr = errors.toString(StandardCharsets.UTF_8);
        assertTrue("Expected error on private struct in public field: " + errStr,
                errStr.contains("cannot expose private struct in public field"));
    }
}
