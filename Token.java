package lexico;

public class Token {
    public final TokenType type;
    public final String lexeme;
    public final Object literal;
    public final int line;

    public Token(TokenType type, String lexeme, Object literal, int line) {
        this.type = type;
        this.lexeme = lexeme;
        this.literal = literal;
        this.line = line;
    }

    @Override
    public String toString() {
        String literalStr = literal == null ? "" : literal.toString();
        return String.format("%-30s | %-15s | %-10s | %-25s",
                type.name() + " (" + type.getCode() + ")",
                lexeme,
                literalStr,
                type.getCategoria());
    }
}