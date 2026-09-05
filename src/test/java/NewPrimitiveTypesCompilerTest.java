import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class NewPrimitiveTypesCompilerTest {
    @Test
    public void supportsShortAndCharScalarsAndArraysTogether() throws Exception {
        String source = ""
                + "short takeShort(short value) { return value; }\n"
                + "short[] takeShortArray(short values[]) { return values; }\n"
                + "char takeChar(char value) { return value; }\n"
                + "char[] takeCharArray(char values[]) { return values; }\n"
                + "void main() { short s; short sa[1]; char c; char ca[1];\n"
                + "    s = takeShort(1); sa[0] = s; takeShortArray(sa);\n"
                + "    c = takeChar('A'); ca[0] = c; takeCharArray(ca); }\n";
        File directory = Files.createTempDirectory("lemonc-new-types").toFile();
        File file = new File(directory, "NewPrimitiveTypes.lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        try {
            Ast.Program.T program = new Parser(new Lexer(file)).parse();
            SemanticVisitor semantic = SemanticVisitor.collecting();
            semantic.visit(program);
            assertTrue(semantic.passOrNot());
            byte[] classBytes = JvmTestSupport.compileToBytes("NewPrimitiveTypes", source);
            assertTrue(JvmTestSupport.hasMethod(classBytes, "takeShort", "(S)S"));
            assertTrue(JvmTestSupport.hasMethod(classBytes, "takeShortArray", "([S)[S"));
            assertTrue(JvmTestSupport.hasMethod(classBytes, "takeChar", "(C)C"));
            assertTrue(JvmTestSupport.hasMethod(classBytes, "takeCharArray", "([C)[C"));
            assertTrue(JvmTestSupport.hasNewArray(classBytes, "short"));
            assertTrue(JvmTestSupport.hasNewArray(classBytes, "char"));
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
