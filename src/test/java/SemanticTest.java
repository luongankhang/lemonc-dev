import org.junit.Assert;
import org.junit.Before;
import site.ilemon.ast.Ast;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.io.IOException;

/**
 * Created by andy on 2019/7/31.
 */
public class SemanticTest {

    private Lexer lexer;
    private Parser parser;

    @Before
    public void init() throws IOException{
        File file = new File("examples/BoolTest01.lemon");
        lexer=new Lexer(file);
        parser = new Parser(lexer);
    }

    @org.junit.Test
    public void testParser() throws IOException{
        Ast.Program.T prog = parser.parse();
        SemanticVisitor visitor = new SemanticVisitor();
        visitor.visit(prog);
        Assert.assertTrue(visitor.passOrNot());
    }

    @org.junit.Test
    public void testIntegerMinValueSemanticCheck() throws IOException {
        File file = File.createTempFile("sem_min_int", ".lemon");
        file.deleteOnExit();
        java.nio.file.Files.writeString(file.toPath(), "void main() { int x; x = -2147483648; }\n", java.nio.charset.StandardCharsets.UTF_8);
        Parser p = new Parser(new Lexer(file));
        Ast.Program.T prog = p.parse();
        SemanticVisitor visitor = new SemanticVisitor();
        visitor.visit(prog);
        Assert.assertTrue(visitor.passOrNot());
    }

    @org.junit.Test
    public void testModLongPromotionPasses() throws IOException {
        File file = File.createTempFile("sem_mod_long", ".lemon");
        file.deleteOnExit();
        java.nio.file.Files.writeString(file.toPath(), "void main() { long a; long b; long c; a = 10; b = 3; c = a % b; }\n", java.nio.charset.StandardCharsets.UTF_8);
        Parser p = new Parser(new Lexer(file));
        Ast.Program.T prog = p.parse();
        SemanticVisitor visitor = new SemanticVisitor();
        visitor.visit(prog);
        Assert.assertTrue(visitor.passOrNot());
    }

    @org.junit.Test
    public void testModLongAssignedToIntFails() throws IOException {
        File file = File.createTempFile("sem_mod_fail", ".lemon");
        file.deleteOnExit();
        java.nio.file.Files.writeString(file.toPath(), "void main() { long a; long b; int c; a = 10; b = 3; c = a % b; }\n", java.nio.charset.StandardCharsets.UTF_8);
        Parser p = new Parser(new Lexer(file));
        Ast.Program.T prog = p.parse();
        SemanticVisitor visitor = SemanticVisitor.collecting();
        visitor.visit(prog);
        Assert.assertFalse(visitor.passOrNot());
    }
}
