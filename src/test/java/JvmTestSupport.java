import site.ilemon.ast.Ast;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared compile/run harness for tests that exercise the JVM backend.
 *
 * <p>Drives the exact compiler pipeline the CLI uses
 * (Lexer → Parser → ModuleLoader → Semantic → Optimizer → LemonIR → JvmBackend)
 * and offers helpers to run the resulting class and to inspect the emitted
 * class-file structure (methods, descriptors, code bytes, max_stack/max_locals).</p>
 */
public final class JvmTestSupport {

    /** Result of a compilation: the directory holding the class and its name. */
    public record CompiledClass(File classDir, String className) {
        public File classFile() {
            return new File(classDir, className + ".class");
        }

        public byte[] classBytes() throws IOException {
            return Files.readAllBytes(classFile().toPath());
        }
    }

    /** Per-method view of an emitted class file. */
    public record MethodInfo(String name, String descriptor, int maxStack, int maxLocals, byte[] code) {
    }

    private JvmTestSupport() {
    }

    /** Compiles a source snippet; the class is written into a fresh temp directory. */
    public static CompiledClass compile(String className, String source) throws Exception {
        File dir = Files.createTempDirectory("lemonc-jvm-test").toFile();
        File file = new File(dir, className + ".lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        return compileFile(file, dir);
    }

    /** Compiles an existing .lemon file into {@code target/lemonc} (like the CLI). */
    public static CompiledClass compile(File sourceFile) throws Exception {
        File dir = new File(JvmBackend.DEFAULT_OUTPUT_DIR);
        return compileFile(sourceFile, dir);
    }

    /** Compiles and immediately returns the emitted class-file bytes. */
    public static byte[] compileToBytes(String className, String source) throws Exception {
        return compile(className, source).classBytes();
    }

    /** Compiles a snippet and returns its program output when run on the JVM. */
    public static String compileAndRun(String className, String source) throws Exception {
        return run(compile(className, source));
    }

    /** Runs a compiled class in a subprocess and returns its normalized stdout. */
    public static String run(CompiledClass compiled) throws Exception {
        return run(compiled.className, compiled.classDir);
    }

    public static String run(String className, File classDir) throws Exception {
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

    // ------------------------------------------------------------ pipeline

    private static CompiledClass compileFile(File sourceFile, File outDir) throws Exception {
        Lexer lexer = new Lexer(sourceFile);
        Parser parser = new Parser(lexer);
        Ast.Program.T program = parser.parse();
        new ModuleLoader().resolve(program, sourceFile.toPath());
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        if (!semantic.passOrNot()) {
            throw new AssertionError("unexpected semantic errors for " + sourceFile + ": "
                    + semantic.getDiagnostics());
        }
        program = new AstOptimizer().optimize(program);
        IrModule irModule = new AstToIrLowerer().lower(program);
        BackendOptions options = new BackendOptions("jvm",
                sourceFile.toPath().toAbsolutePath().normalize(),
                outDir.toPath(), null, false);
        new JvmBackend().emit(irModule, options);
        String name = sourceFile.getName();
        return new CompiledClass(outDir, name.substring(0, name.length() - ".lemon".length()));
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), executable).getPath();
    }

    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int read;
        while ((read = stream.read(data)) != -1) {
            buffer.write(data, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------- class-file reading

    /** Parses the methods of a class file on disk: name, descriptor, Code limits and bytes. */
    public static List<MethodInfo> readMethods(File classFile) throws IOException {
        return readMethods(Files.readAllBytes(classFile.toPath()));
    }

    /** Parses the methods of a class file: name, descriptor, Code limits and bytes. */
    public static List<MethodInfo> readMethods(byte[] classBytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes));
        in.readInt();                       // magic
        in.readUnsignedShort();             // minor
        in.readUnsignedShort();             // major
        int cpCount = in.readUnsignedShort();
        String[] utf8s = new String[cpCount];
        int i = 1;
        while (i < cpCount) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> {
                    int len = in.readUnsignedShort();
                    byte[] raw = new byte[len];
                    in.readFully(raw);
                    utf8s[i] = new String(raw, StandardCharsets.UTF_8);
                }
                case 3, 4 -> in.skipBytes(4);
                case 5, 6 -> in.skipBytes(8);
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 15 -> in.skipBytes(3);
                default -> throw new IOException("unexpected constant pool tag " + tag);
            }
            i++;
            if (tag == 5 || tag == 6) {
                i++; // long/double occupy two pool entries
            }
        }
        int codeNameIndex = -1;
        for (int k = 1; k < cpCount; k++) {
            if ("Code".equals(utf8s[k])) {
                codeNameIndex = k;
                break;
            }
        }
        in.readUnsignedShort();             // access flags
        in.readUnsignedShort();             // this class
        in.readUnsignedShort();             // super class
        int interfaceCount = in.readUnsignedShort();
        in.skipBytes(interfaceCount * 2);
        int fieldCount = in.readUnsignedShort();
        for (int f = 0; f < fieldCount; f++) {
            skipMember(in);
        }
        int methodCount = in.readUnsignedShort();
        List<MethodInfo> methods = new ArrayList<>();
        for (int m = 0; m < methodCount; m++) {
            in.readUnsignedShort();         // access flags
            String name = utf8s[in.readUnsignedShort()];
            String descriptor = utf8s[in.readUnsignedShort()];
            int attributeCount = in.readUnsignedShort();
            int maxStack = 0;
            int maxLocals = 0;
            byte[] code = new byte[0];
            for (int a = 0; a < attributeCount; a++) {
                int attributeName = in.readUnsignedShort();
                int length = in.readInt();
                if (attributeName == codeNameIndex) {
                    maxStack = in.readUnsignedShort();
                    maxLocals = in.readUnsignedShort();
                    int codeLength = in.readInt();
                    code = new byte[codeLength];
                    in.readFully(code);
                    int exceptionCount = in.readUnsignedShort();
                    in.skipBytes(exceptionCount * 8);
                    int codeAttributes = in.readUnsignedShort();
                    for (int ca = 0; ca < codeAttributes; ca++) {
                        in.readUnsignedShort();
                        in.skipBytes(in.readInt());
                    }
                } else {
                    in.skipBytes(length);
                }
            }
            methods.add(new MethodInfo(name, descriptor, maxStack, maxLocals, code));
        }
        return methods;
    }

    private static void skipMember(DataInputStream in) throws IOException {
        in.readUnsignedShort();             // access flags
        in.readUnsignedShort();             // name
        in.readUnsignedShort();             // descriptor
        int attributeCount = in.readUnsignedShort();
        for (int a = 0; a < attributeCount; a++) {
            in.readUnsignedShort();
            in.skipBytes(in.readInt());
        }
    }

    public static MethodInfo method(List<MethodInfo> methods, String name) {
        for (MethodInfo method : methods) {
            if (method.name().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("no method named " + name + " in " + methods);
    }

    /** ASCII/UTF-8 text search over the raw class bytes (descriptors live in the pool). */
    public static boolean containsText(byte[] classBytes, String ascii) {
        return new String(classBytes, StandardCharsets.ISO_8859_1).contains(ascii);
    }

    /** True when a method with the given (name, descriptor) pair is declared. */
    public static boolean hasMethod(byte[] classBytes, String name, String descriptor) throws IOException {
        for (MethodInfo method : readMethods(classBytes)) {
            if (method.name().equals(name) && method.descriptor().equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    /** True when any method's code contains the given JVM mnemonic (opcode only). */
    public static boolean hasMnemonic(byte[] classBytes, String mnemonic) throws IOException {
        Integer opcode = OPCODES.get(mnemonic);
        if (opcode == null) {
            throw new IllegalArgumentException("no opcode table entry for " + mnemonic);
        }
        return anyMethodHasOpcode(classBytes, opcode);
    }

    /** True when any method's code contains {@code newarray} with the given element atype. */
    public static boolean hasNewArray(byte[] classBytes, String elementType) throws IOException {
        Integer atype = ATYPES.get(elementType);
        if (atype == null) {
            throw new IllegalArgumentException("no newarray atype for " + elementType);
        }
        return anyMethodHasSequence(classBytes, 0xBC /* newarray */, atype);
    }

    /** True when any method's code contains {@code anewarray} (element class comes from the pool). */
    public static boolean hasANewArray(byte[] classBytes) throws IOException {
        return anyMethodHasOpcode(classBytes, 0xBD);
    }

    /** JVM opcodes used by the structural tests. */
    private static final java.util.Map<String, Integer> OPCODES = java.util.Map.ofEntries(
            // loads / stores
            java.util.Map.entry("iload", 0x15), java.util.Map.entry("lload", 0x16),
            java.util.Map.entry("fload", 0x17), java.util.Map.entry("dload", 0x18),
            java.util.Map.entry("aload", 0x19),
            java.util.Map.entry("istore", 0x36), java.util.Map.entry("lstore", 0x37),
            java.util.Map.entry("fstore", 0x38), java.util.Map.entry("dstore", 0x39),
            java.util.Map.entry("astore", 0x3A),
            // array access
            java.util.Map.entry("iaload", 0x2E), java.util.Map.entry("laload", 0x2F),
            java.util.Map.entry("faload", 0x30), java.util.Map.entry("daload", 0x31),
            java.util.Map.entry("aaload", 0x32), java.util.Map.entry("baload", 0x33),
            java.util.Map.entry("caload", 0x34), java.util.Map.entry("saload", 0x35),
            java.util.Map.entry("iastore", 0x4F), java.util.Map.entry("lastore", 0x50),
            java.util.Map.entry("fastore", 0x51), java.util.Map.entry("dastore", 0x52),
            java.util.Map.entry("aastore", 0x53), java.util.Map.entry("bastore", 0x54),
            java.util.Map.entry("castore", 0x55), java.util.Map.entry("sastore", 0x56),
            java.util.Map.entry("arraylength", 0xBE),
            // integer / long arithmetic
            java.util.Map.entry("iadd", 0x60), java.util.Map.entry("ladd", 0x61),
            java.util.Map.entry("fadd", 0x62), java.util.Map.entry("dadd", 0x63),
            java.util.Map.entry("isub", 0x64), java.util.Map.entry("lsub", 0x65),
            java.util.Map.entry("fsub", 0x66), java.util.Map.entry("dsub", 0x67),
            java.util.Map.entry("imul", 0x68), java.util.Map.entry("lmul", 0x69),
            java.util.Map.entry("fmul", 0x6A), java.util.Map.entry("dmul", 0x6B),
            java.util.Map.entry("idiv", 0x6C), java.util.Map.entry("ldiv", 0x6D),
            java.util.Map.entry("fdiv", 0x6E), java.util.Map.entry("ddiv", 0x6F),
            java.util.Map.entry("irem", 0x70), java.util.Map.entry("lrem", 0x71),
            java.util.Map.entry("frem", 0x72), java.util.Map.entry("drem", 0x73),
            java.util.Map.entry("ineg", 0x74), java.util.Map.entry("lneg", 0x75),
            java.util.Map.entry("fneg", 0x76), java.util.Map.entry("dneg", 0x77),
            // comparisons / conversions
            java.util.Map.entry("lcmp", 0x94), java.util.Map.entry("fcmpl", 0x95),
            java.util.Map.entry("fcmpg", 0x96), java.util.Map.entry("dcmpl", 0x97),
            java.util.Map.entry("dcmpg", 0x98),
            java.util.Map.entry("i2l", 0x85), java.util.Map.entry("i2f", 0x86),
            java.util.Map.entry("i2d", 0x87), java.util.Map.entry("l2i", 0x88),
            java.util.Map.entry("l2f", 0x89), java.util.Map.entry("l2d", 0x8A),
            java.util.Map.entry("f2i", 0x8B), java.util.Map.entry("f2l", 0x8C),
            java.util.Map.entry("f2d", 0x8D), java.util.Map.entry("d2i", 0x8E),
            java.util.Map.entry("d2l", 0x8F), java.util.Map.entry("d2f", 0x90),
            java.util.Map.entry("i2b", 0x91), java.util.Map.entry("i2c", 0x92),
            java.util.Map.entry("i2s", 0x93),
            // returns
            java.util.Map.entry("ireturn", 0xAC), java.util.Map.entry("lreturn", 0xAD),
            java.util.Map.entry("freturn", 0xAE), java.util.Map.entry("dreturn", 0xAF),
            java.util.Map.entry("areturn", 0xB0), java.util.Map.entry("return", 0xB1),
            // control flow
            java.util.Map.entry("goto", 0xA7),
            java.util.Map.entry("ifeq", 0x99), java.util.Map.entry("ifne", 0x9A),
            java.util.Map.entry("iflt", 0x9B), java.util.Map.entry("ifge", 0x9C),
            java.util.Map.entry("ifgt", 0x9D), java.util.Map.entry("ifle", 0x9E),
            java.util.Map.entry("if_icmpeq", 0x9F), java.util.Map.entry("if_icmpne", 0xA0),
            java.util.Map.entry("if_icmplt", 0xA1), java.util.Map.entry("if_icmpge", 0xA2),
            java.util.Map.entry("if_icmpgt", 0xA3), java.util.Map.entry("if_icmple", 0xA4),
            java.util.Map.entry("if_acmpeq", 0xA5), java.util.Map.entry("if_acmpne", 0xA6),
            // constants / fields / calls
            java.util.Map.entry("ldc", 0x12), java.util.Map.entry("ldc_w", 0x13),
            java.util.Map.entry("ldc2_w", 0x14),
            java.util.Map.entry("getstatic", 0xB2), java.util.Map.entry("putstatic", 0xB3),
            java.util.Map.entry("invokevirtual", 0xB6), java.util.Map.entry("invokespecial", 0xB7),
            java.util.Map.entry("invokestatic", 0xB8), java.util.Map.entry("invokeinterface", 0xB9),
            java.util.Map.entry("pop", 0x57), java.util.Map.entry("pop2", 0x58),
            java.util.Map.entry("dup", 0x59));

    /** newarray atype constants (JVM spec 6.5 newarray). */
    private static final java.util.Map<String, Integer> ATYPES = java.util.Map.ofEntries(
            java.util.Map.entry("boolean", 4), java.util.Map.entry("char", 5),
            java.util.Map.entry("float", 6), java.util.Map.entry("double", 7),
            java.util.Map.entry("byte", 8), java.util.Map.entry("short", 9),
            java.util.Map.entry("int", 10), java.util.Map.entry("long", 11));

    /** True when the class file declares no fields (constants are inlined, not stored). */
    public static boolean hasNoFields(byte[] classBytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes));
        in.readInt();                       // magic
        in.readUnsignedShort();             // minor
        in.readUnsignedShort();             // major
        int cpCount = in.readUnsignedShort();
        int i = 1;
        while (i < cpCount) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> in.skipBytes(in.readUnsignedShort());
                case 3, 4 -> in.skipBytes(4);
                case 5, 6 -> in.skipBytes(8);
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 15 -> in.skipBytes(3);
                default -> throw new IOException("unexpected constant pool tag " + tag);
            }
            i++;
            if (tag == 5 || tag == 6) {
                i++; // long/double occupy two pool entries
            }
        }
        in.readUnsignedShort();             // access flags
        in.readUnsignedShort();             // this class
        in.readUnsignedShort();             // super class
        int interfaceCount = in.readUnsignedShort();
        in.skipBytes(interfaceCount * 2);
        return in.readUnsignedShort() == 0; // field count
    }

    /** True when the opcode appears in the code of any parsed method. */
    public static boolean anyMethodHasOpcode(byte[] classBytes, int opcode) throws IOException {
        for (MethodInfo method : readMethods(classBytes)) {
            if (hasSequence(method.code(), opcode)) {
                return true;
            }
        }
        return false;
    }

    /** True when the byte sequence appears in the code of any parsed method. */
    public static boolean anyMethodHasSequence(byte[] classBytes, int... bytes) throws IOException {
        for (MethodInfo method : readMethods(classBytes)) {
            if (hasSequence(method.code(), bytes)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasSequence(byte[] code, int... needle) {
        outer:
        for (int i = 0; i + needle.length <= code.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if ((code[i + j] & 0xFF) != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
