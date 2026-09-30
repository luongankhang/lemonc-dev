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
 * End-to-end integration tests for multi-module struct scoping in LemonC.
 * Validates:
 * 1. Structs from distinct modules (user.lemon, product.lemon) maintain distinct types and layouts.
 * 2. Field isolation: User cannot access Product fields; Product cannot access User fields.
 * 3. Cross-module pub struct usage by value and by pointer.
 * 4. Private struct and private function access rejection across modules.
 * 5. Value copy semantics, parameter passing, return values, pointer dereference (->), and ARC.
 * 6. Dual-backend parity: JVM and native C backends produce byte-for-byte identical output.
 * 7. Negative compile-fail diagnostics for type mismatches, unknown fields, and scope violations.
 */
public class ModuleStructScopeTest {

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

    private void prepareModuleFiles(Path dir) throws Exception {
        Files.copy(Path.of("examples/module_struct_scope/user.lemon"), dir.resolve("user.lemon"));
        Files.copy(Path.of("examples/module_struct_scope/product.lemon"), dir.resolve("product.lemon"));
    }

    // ============================================ Positive End-to-End Tests

    @Test
    public void testModuleStructScopeExample_DualBackendParity() throws Exception {
        Path main = Path.of("examples/module_struct_scope/main.lemon").toAbsolutePath();
        String jvmOut = compileAndRunJvm(main);
        String nativeOut = compileAndRunNative(main);

        String expected =
                "Created user: id=101, age=25, score=500\n"
                + "Created product: sku=9001, price=50, stock=20\n"
                + "After copy - u1 score: 500, u2 score: 650\n"
                + "After copy - p1 price: 50, p2 price: 80\n"
                + "User[id=102, age=26, score=650]\n"
                + "Product[sku=9002, price=80, stock=15]\n"
                + "Updated u1 score via pointer: 650\n"
                + "Updated p1 price via pointer: 40\n"
                + "Direct arrow access - u1 age: 30, p1 stock: 25\n"
                + "Calculations - user score: 650, inventory value: 1000\n"
                + "Report summary: 101, 650, 9001, 40\n";

        assertEquals("JVM output must match expected", expected, jvmOut);
        assertEquals("Native output must match JVM output byte-for-byte", jvmOut, nativeOut);
    }

    @Test
    public void testModuleStructScopeExample_WithArcVerify() throws Exception {
        Path main = Path.of("examples/module_struct_scope/main.lemon").toAbsolutePath();
        String jvmOut = compileAndRunJvm(main, "--arc", "--arc-verify");
        String nativeOut = compileAndRunNative(main, "--arc", "--arc-verify");

        String expected =
                "Created user: id=101, age=25, score=500\n"
                + "Created product: sku=9001, price=50, stock=20\n"
                + "After copy - u1 score: 500, u2 score: 650\n"
                + "After copy - p1 price: 50, p2 price: 80\n"
                + "User[id=102, age=26, score=650]\n"
                + "Product[sku=9002, price=80, stock=15]\n"
                + "Updated u1 score via pointer: 650\n"
                + "Updated p1 price via pointer: 40\n"
                + "Direct arrow access - u1 age: 30, p1 stock: 25\n"
                + "Calculations - user score: 650, inventory value: 1000\n"
                + "Report summary: 101, 650, 9001, 40\n";

        assertEquals("JVM output with ARC must match expected", expected, jvmOut);
        assertEquals("Native output with ARC must match JVM output", jvmOut, nativeOut);
    }

    // ============================================ Negative Tests: Field Isolation

    @Test
    public void testUserCannotAccessProductField() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativeFieldUser.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "import prod = @import(\"product.lemon\");\n"
                + "void main() {\n"
                + "    struct User u = user.createUser(1, 20, 100);\n"
                + "    u.sku = 999;\n" // User has no sku field!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject invalid field access", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected unknown field error for u.sku: " + errStr,
                    errStr.contains("has no field 'sku'") || errStr.contains("E2001"));
        }
    }

    @Test
    public void testProductCannotAccessUserField() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativeFieldProduct.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "import prod = @import(\"product.lemon\");\n"
                + "void main() {\n"
                + "    struct Product p = prod.createProduct(101, 50, 10);\n"
                + "    p.age = 30;\n" // Product has no age field!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject invalid field access", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected unknown field error for p.age: " + errStr,
                    errStr.contains("has no field 'age'") || errStr.contains("E2001"));
        }
    }

    @Test
    public void testUserPointerCannotAccessProductFieldViaArrow() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativeArrowUser.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "import prod = @import(\"product.lemon\");\n"
                + "void main() {\n"
                + "    struct User u = user.createUser(1, 20, 100);\n"
                + "    struct User* ptr = &u;\n"
                + "    ptr->price = 100;\n" // User has no price field!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject invalid arrow field access", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected unknown field error for ptr->price: " + errStr,
                    errStr.contains("has no field 'price'") || errStr.contains("E2001"));
        }
    }

    // ============================================ Negative Tests: Type Mismatches

    @Test
    public void testStructValueAssignmentMismatch() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativeAssignMismatch.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "import prod = @import(\"product.lemon\");\n"
                + "void main() {\n"
                + "    struct User u = user.createUser(1, 20, 100);\n"
                + "    struct Product p = prod.createProduct(101, 50, 10);\n"
                + "    u = p;\n" // Assigning Product to User must fail!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject struct assignment mismatch", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected type assignment error for u = p: " + errStr,
                    errStr.contains("E3001") || errStr.contains("cannot assign"));
        }
    }

    @Test
    public void testStructPointerAssignmentMismatch() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativePtrAssignMismatch.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "import prod = @import(\"product.lemon\");\n"
                + "void main() {\n"
                + "    struct User u = user.createUser(1, 20, 100);\n"
                + "    struct Product p = prod.createProduct(101, 50, 10);\n"
                + "    struct User* uPtr;\n"
                + "    uPtr = &p;\n" // Pointing User* to Product must fail!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject pointer type mismatch", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected type mismatch error for uPtr = &p: " + errStr,
                    errStr.contains("E3001") || errStr.contains("E3013") || errStr.contains("cannot assign"));
        }
    }

    @Test
    public void testFunctionArgumentStructTypeMismatch() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativeArgMismatch.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "import prod = @import(\"product.lemon\");\n"
                + "void main() {\n"
                + "    struct Product p = prod.createProduct(101, 50, 10);\n"
                + "    user.getUserScore(p);\n" // Passing Product to function expecting User!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject function argument type mismatch", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected argument type mismatch error: " + errStr,
                    errStr.contains("E3003") || errStr.contains("argument"));
        }
    }

    @Test
    public void testFunctionPointerArgumentStructTypeMismatch() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativePtrArgMismatch.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "import prod = @import(\"product.lemon\");\n"
                + "void main() {\n"
                + "    struct Product p = prod.createProduct(101, 50, 10);\n"
                + "    user.updateUserScore(&p, 50);\n" // Passing struct Product* to struct User* param!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject pointer argument type mismatch", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected pointer argument type mismatch error: " + errStr,
                    errStr.contains("E3003") || errStr.contains("argument"));
        }
    }

    // ============================================ Negative Tests: Private Access Rejection

    @Test
    public void testPrivateStructAccessRejection_BareName() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativePrivateStructBare.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "void main() {\n"
                + "    struct UserSecret secret;\n" // Private struct declared in user.lemon!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject private struct access", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected private struct rejection: " + errStr,
                    errStr.contains("private struct") || errStr.contains("E2005"));
        }
    }

    @Test
    public void testPrivateStructAccessRejection_QualifiedName() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativePrivateStructQualified.lemon",
                "import prod = @import(\"product.lemon\");\n"
                + "void main() {\n"
                + "    struct prod.ProductInternalTag tag;\n" // Private struct qualified!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject qualified private struct access", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected private struct rejection: " + errStr,
                    errStr.contains("private struct") || errStr.contains("E2005"));
        }
    }

    @Test
    public void testPrivateFunctionAccessRejection() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath();
        prepareModuleFiles(dir);

        Path main = write(dir, "NegativePrivateFunc.lemon",
                "import user = @import(\"user.lemon\");\n"
                + "void main() {\n"
                + "    user.internalUserHash(42);\n" // Private function in user.lemon!
                + "}\n");

        for (String target : new String[]{"jvm", "c"}) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = compile(main, target, errors);
            assertEquals("--target " + target + " must reject private function access", 1, exitCode);
            String errStr = errors.toString(StandardCharsets.UTF_8);
            assertTrue("Expected undefined/private function rejection: " + errStr,
                    errStr.contains("undefined function") || errStr.contains("E2002") || errStr.contains("E2005"));
        }
    }
}
