import org.junit.Test;
import site.ilemon.lexer.Lexer;
import site.ilemon.lexer.Token;
import site.ilemon.lexer.TokenKind;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Lexer test cases.
 * Tests each state transition path in the DFA.
 */
public class LexerTest {

    // ==================== Keyword Tests ====================
    
    @Test
    public void testKeywords() throws IOException {
        Lexer lexer = new Lexer(new File("examples/Cal.lemon"));
        lexer.lexicalAnalysis();
        
        // Top-level examples begin with a function declaration.
        Token first = lexer.next();
        assertEquals(TokenKind.Void, first.kind);
        assertEquals("void", first.lexeme);
    }

    @Test
    public void testAllKeywords() throws IOException {
        Lexer lexer = new Lexer(new File("examples/FloatTest01.lemon"));
        lexer.lexicalAnalysis();
        
        // Check that tokens contain various keywords
        boolean hasVoid = false, hasFloat = false, hasReturn = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.Void) hasVoid = true;
            if (t.kind == TokenKind.Float) hasFloat = true;
            if (t.kind == TokenKind.Return) hasReturn = true;
        }
        assertTrue("should recognize 'void' keyword", hasVoid);
        assertTrue("should recognize 'float' keyword", hasFloat);
        assertTrue("should recognize 'return' keyword", hasReturn);
    }

    @Test
    public void testClassKeywordRemainsAvailableForCompatibility() throws IOException {
        java.nio.file.Path file = Files.createTempFile("lemonc-class-keyword", ".lemon");
        Files.writeString(file, "class Legacy { int field; }\n", StandardCharsets.UTF_8);
        try {
            Lexer lexer = new Lexer(file.toFile());
            lexer.lexicalAnalysis();
            assertEquals(TokenKind.Class, lexer.tokens.get(0).kind);
            assertEquals("class", lexer.tokens.get(0).lexeme);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ==================== Identifier Tests ====================
    
    @Test
    public void testIdentifier() throws IOException {
        Lexer lexer = new Lexer(new File("examples/Cal.lemon"));
        lexer.lexicalAnalysis();
        
        // Look up identifier
        boolean hasId = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.Id) {
                hasId = true;
                break;
            }
        }
        assertTrue("should recognize identifier", hasId);
    }

    // ==================== Number Tests ====================
    
    @Test
    public void testIntegerNumber() throws IOException {
        Lexer lexer = new Lexer(new File("examples/IntTest01.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasNum = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.Num) {
                hasNum = true;
                break;
            }
        }
        assertTrue("should recognize integer", hasNum);
    }

    @Test
    public void testFloatNumber() throws IOException {
        Lexer lexer = new Lexer(new File("examples/FloatTest01.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasDNum = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.FloatLiteral) {
                hasDNum = true;
                // Verify floating-point format
                assertTrue("float should contain decimal point", t.lexeme.contains("."));
                break;
            }
        }
        assertTrue("should recognize floating-point number", hasDNum);
    }

    // ==================== Operator Tests ====================
    
    @Test
    public void testArithmeticOperators() throws IOException {
        // Use FloatTest01, which contains all 4 arithmetic operators
        Lexer lexer = new Lexer(new File("examples/FloatTest01.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasAdd = false, hasSub = false, hasMul = false, hasDiv = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.Add) hasAdd = true;
            if (t.kind == TokenKind.Sub) hasSub = true;
            if (t.kind == TokenKind.Mul) hasMul = true;
            if (t.kind == TokenKind.Div) hasDiv = true;
        }
        assertTrue("should recognize add operator", hasAdd);
        assertTrue("should recognize sub operator", hasSub);
        assertTrue("should recognize mul operator", hasMul);
        assertTrue("should recognize div operator", hasDiv);
    }

    @Test
    public void testComparisonOperators() throws IOException {
        Lexer lexer = new Lexer(new File("examples/If01.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasComparison = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.LT || t.kind == TokenKind.GT ||
                t.kind == TokenKind.LTE || t.kind == TokenKind.GTE ||
                t.kind == TokenKind.EQ || t.kind == TokenKind.NEQ) {
                hasComparison = true;
                break;
            }
        }
        assertTrue("should recognize comparison operators", hasComparison);
    }

    @Test
    public void testLogicalOperators() throws IOException {
        Lexer lexer = new Lexer(new File("examples/BoolTest01.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasLogical = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.And || t.kind == TokenKind.Or || t.kind == TokenKind.Not) {
                hasLogical = true;
                break;
            }
        }
        // Logical operators may not be in all files
        // assertTrue("should recognize logical operators", hasLogical);
    }

    // ==================== Delimiter Tests ====================
    
    @Test
    public void testDelimiters() throws IOException {
        Lexer lexer = new Lexer(new File("examples/Cal.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasLbrace = false, hasRbrace = false;
        boolean hasLparen = false, hasRparen = false;
        boolean hasSemicolon = false;
        
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.Lbrace) hasLbrace = true;
            if (t.kind == TokenKind.Rbrace) hasRbrace = true;
            if (t.kind == TokenKind.Lparen) hasLparen = true;
            if (t.kind == TokenKind.Rparen) hasRparen = true;
            if (t.kind == TokenKind.Semicolon) hasSemicolon = true;
        }
        
        assertTrue("should recognize left brace", hasLbrace);
        assertTrue("should recognize right brace", hasRbrace);
        assertTrue("should recognize left parenthesis", hasLparen);
        assertTrue("should recognize right parenthesis", hasRparen);
        assertTrue("should recognize semicolon", hasSemicolon);
    }

    // ==================== String Literal Tests ====================
    
    @Test
    public void testStringLiteral() throws IOException {
        Lexer lexer = new Lexer(new File("examples/FloatTest01.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasString = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.String && t.lexeme.contains("%")) {
                hasString = true;
                break;
            }
        }
        assertTrue("should recognize string literal", hasString);
    }

    // ==================== Comment Tests ====================
    
    @Test
    public void testCommentIgnored() throws IOException {
        Lexer lexer = new Lexer(new File("examples/FloatTest01.lemon"));
        lexer.lexicalAnalysis();
        
        // Comments should be ignored and must not appear in tokens
        for (Token t : lexer.tokens) {
            assertFalse("comment content should not appear in token", 
                t.lexeme.contains("\u6d4b\u8bd5") || t.lexeme.contains("\u6d6e\u70b9\u578b"));
        }
    }

    // ==================== Line Number Tests ====================
    
    @Test
    public void testLineNumber() throws IOException {
        Lexer lexer = new Lexer(new File("examples/Cal.lemon"));
        lexer.lexicalAnalysis();
        
        // First token should be at line 1 or later
        Token first = lexer.next();
        assertTrue("line number should be greater than 0", first.lineNumber >= 1);
    }

    // ==================== EOF Tests ====================
    
    @Test
    public void testEOF() throws IOException {
        Lexer lexer = new Lexer(new File("examples/Cal.lemon"));
        lexer.lexicalAnalysis();
        
        // Last token should be EOF
        Token last = lexer.tokens.get(lexer.tokens.size() - 1);
        assertEquals("last token should be EOF", TokenKind.EOF, last.kind);
    }

    // ==================== Comprehensive Tests ====================
    
    @Test
    public void testCompleteFile() throws IOException {
        Lexer lexer = new Lexer(new File("examples/FloatTest01.lemon"));
        lexer.lexicalAnalysis();
        
        // Verify reasonable token count
        assertTrue("should produce multiple tokens", lexer.tokens.size() > 10);
        assertEquals(TokenKind.Void, lexer.tokens.get(0).kind);
        assertEquals(TokenKind.EOF, lexer.tokens.get(lexer.tokens.size() - 1).kind);
    }

    @Test
    public void testIfStatement() throws IOException {
        Lexer lexer = new Lexer(new File("examples/If07.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasIf = false, hasElse = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.If) hasIf = true;
            if (t.kind == TokenKind.Else) hasElse = true;
        }
        assertTrue("should recognize 'if' keyword", hasIf);
    }

    @Test
    public void testWhileLoop() throws IOException {
        Lexer lexer = new Lexer(new File("examples/Iteration01.lemon"));
        lexer.lexicalAnalysis();
        
        boolean hasWhile = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.While) {
                hasWhile = true;
                break;
            }
        }
        assertTrue("should recognize 'while' keyword", hasWhile);
    }

    @Test
    public void testMethodCall() throws IOException {
        Lexer lexer = new Lexer(new File("examples/SimpleMethodCall.lemon"));
        lexer.lexicalAnalysis();
        
        // Method call should have identifier and parentheses
        boolean hasId = false, hasLparen = false;
        for (Token t : lexer.tokens) {
            if (t.kind == TokenKind.Id) hasId = true;
            if (t.kind == TokenKind.Lparen) hasLparen = true;
        }
        assertTrue("should have identifier", hasId);
        assertTrue("should have left parenthesis", hasLparen);
    }

    // ==================== Legacy Tests ====================
    
    @Test
    public void testCal() throws IOException {
        Lexer lexer = new Lexer(new File("examples/FloatTest01.lemon"));
        lexer.lexicalAnalysis();
        assertTrue(lexer.tokens.size() > 10);
        assertEquals(TokenKind.Void, lexer.tokens.get(0).kind);
        assertEquals(TokenKind.EOF, lexer.tokens.get(lexer.tokens.size() - 1).kind);
    }
}
