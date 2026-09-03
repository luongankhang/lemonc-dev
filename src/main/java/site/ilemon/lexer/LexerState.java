package site.ilemon.lexer;

/**
 * Lexer state enumeration.
 * Defines all DFA states.
 */
public enum LexerState {
    START,          // Initial state
    IN_ID,          // Reading an identifier
    IN_NUM,         // Reading an integer
    IN_FLOAT,       // Reading a floating-point number (fractional part)
    IN_STRING,      // Reading a string literal
    IN_COMMENT,     // Reading a single-line comment
    IN_ASSIGN,      // Read '=', could be '=' or '=='
    IN_LT,          // Read '<', could be '<' or '<='
    IN_GT,          // Read '>', could be '>' or '>='
    IN_NOT,         // Read '!', could be '!' or '!='
    IN_AND,         // Read '&', expecting '&&'
    IN_OR,          // Read '|', expecting '||'
    IN_DIV,         // Read '/', could be '/' or '//'
    DONE,           // Finished a token
    ERROR           // Error state
}
