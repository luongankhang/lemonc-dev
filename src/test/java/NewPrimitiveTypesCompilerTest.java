import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ByteCodeGenerator;
import site.ilemon.codegen.TranslatorVisitor;
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
        String source = "class NewPrimitiveTypes {\n"
                + "    short takeShort(short value) { return value; }\n"
                + "    short[] takeShortArray(short values[]) { return values; }\n"
                + "    char takeChar(char value) { return value; }\n"
                + "    char[] takeCharArray(char values[]) { return values; }\n"
                + "    void main() { short s; short sa[1]; char c; char ca[1];\n"
                + "        s = takeShort(1); sa[0] = s; takeShortArray(sa);\n"
                + "        c = takeChar('A'); ca[0] = c; takeCharArray(ca); }\n"
                + "}\n";
        File directory = Files.createTempDirectory("lemonc-new-types").toFile();
        File file = new File(directory, "NewPrimitiveTypes.lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        try {
            Ast.Program.T program = new Parser(new Lexer(file)).parse();
            SemanticVisitor semantic = SemanticVisitor.collecting();
            semantic.visit(program);
            assertTrue(semantic.passOrNot());
            TranslatorVisitor translator = new TranslatorVisitor();
            translator.visit(program);
            ByteCodeGenerator generator = new ByteCodeGenerator();
            generator.visit(translator.prog);
            String jasmin = Files.readString(generator.getOutputFile().toPath());
            assertTrue(jasmin.contains("takeShort(S)S"));
            assertTrue(jasmin.contains("takeShortArray([S)[S"));
            assertTrue(jasmin.contains("takeChar(C)C"));
            assertTrue(jasmin.contains("takeCharArray([C)[C"));
            assertTrue(jasmin.contains("newarray short"));
            assertTrue(jasmin.contains("newarray char"));
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
