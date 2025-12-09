package lexico;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static lexico.TokenType.*;

class Scanner {
	private static final Map<String, TokenType> KEYWORDS = initKeywords();

	private final String source;
	private final List<Token> tokens = new ArrayList<>();
	private int start = 0;
	private int current = 0;
	private int line = 1;

	Scanner(String source) {
		this.source = source;
	}

	List<Token> scanTokens() {
		while (!isAtEnd()) {
			start = current;
			scanToken();
		}
		return tokens;
	}

	private void scanToken() {
		char c = advance();
		switch (c) {
			case '(': addToken(PAREN_IZQ); break;
			case ')': addToken(PAREN_DER); break;
			case '{': addToken(LLAVE_IZQ); break;
			case '}': addToken(LLAVE_DER); break;
			case '[': addToken(CORCHETE_IZQ); break;
			case ']': addToken(CORCHETE_DER); break;
			case ',': addToken(COMA); break;
			case '.': addToken(PUNTO); break;
			case '-': addToken(MENOS); break;
			case '+': addToken(SUMA); break;
			case '*': addToken(ASTERISCO); break;
			case '%': addToken(MOD); break;
			case '\'': character(); break;
			case '"': string(); break;
			case ':': addToken(match(')') ? SONRISA : DOS_PUNTOS); break;
			case '!':
				if (match('=')) addToken(DIFERENTE);
				else addToken(ERROR, "Unexpected '" + c + "'");
				break;
			case '=': addToken(match('=') ? EQUIVALE : IGUAL); break;
			case '<': addToken(match('=') ? MENOR_QUE : MENOR); break;
			case '>': addToken(match('=') ? MAYOR_QUE : MAYOR); break;
			case '&':
				if (match('&')) addToken(AND);
				else addToken(ERROR, "Unexpected '" + c + "'");
				break;
			case '|':
				if (match('|')) addToken(OR);
				else addToken(ERROR, "Unexpected '" + c + "'");
				break;
			case '/':
				if (match('/')) {
					// Comentario de una línea
					commentOneLine();
				} else if (match('*')) {
					// Comentario multilínea
					commentMultiLine();
				} else {
					addToken(DIVISION);
				}
				break;
			case ' ':
			case '\r':
			case '\t':
				break;
			case '\n':
				line++;
				break;
			default:
				if (c == 'a' && checkKeyword("adios:(")) {
					addToken(ADIOS_TRISTE);
				} else if (isDigit(c)) {
					number();
				} else if (isAlpha(c)) {
					identifier();
				} else {
					addToken(ERROR, "Unexpected '" + c + "'");
				}
				break;
		}
	}

	private void commentOneLine() {
		// Avanzar hasta el final de la línea
		while (peek() != '\n' && !isAtEnd()) {
			advance();
		}
		// Agregar el comentario como token
		String text = source.substring(start, current);
		addToken(COMENTARIO_LINEA, text);
	}

	private void commentMultiLine() {
		int startLine = line;

		// Avanzar hasta encontrar */
		while (!isAtEnd()) {
			if (peek() == '\n') {
				line++;
			}

			if (peek() == '*' && peekNext() == '/') {
				advance(); // consume *
				advance(); // consume /
				break;
			}

			advance();
		}

		// Verificar si el comentario se cerró
		if (isAtEnd() && (current < 2 || source.charAt(current - 2) != '*' || source.charAt(current - 1) != '/')) {
			addToken(ERROR, "Comentario multilínea sin cerrar (inicia en línea " + startLine + ")");
			return;
		}

		String text = source.substring(start, current);
		addToken(COMENTARIO_MULTILINEA, text);
	}

	private void character() {
		if (isAtEnd() || !hasNext()) {
			addToken(ERROR, "Unexpected '" + source.substring(start, current) + "'");
			return;
		}

		char value = advance();

		if (peek() != '\'') {
			addToken(ERROR, "Unexpected '" + source.substring(start, current) + "'");
			return;
		}

		advance();
		addToken(CHAR, value);
	}

	private void identifier() {
		while (isAlphaNumeric(peek()) || peek() == '_') {
			advance();
		}

		String text = source.substring(start, current);
		TokenType type = KEYWORDS.get(text);

		if (type != null) {
			addToken(type);
		} else if (isUppercaseIdentifier(text)) {
			addToken(IDENTIFICADOR_MAYUSCULA);
		} else {
			addToken(IDENTIFICADOR);
		}
	}

	private boolean isUppercaseIdentifier(String id) {
		if (id.isEmpty()) return false;

		char first = id.charAt(0);
		if (first < 'A' || first > 'Z') return false;

		for (int i = 1; i < id.length(); i++) {
			char c = id.charAt(i);
			if (c >= 'a' && c <= 'z') return false;
			if (!((c >= 'A' && c <= 'Z') || isDigit(c) || c == '_')) return false;
		}
		return true;
	}

	private void number() {
		while (isDigit(peek())) advance();

		boolean isReal = false;
		if (peek() == '.' && isDigit(peekNext())) {
			isReal = true;
			advance();
			while (isDigit(peek())) advance();
		}

		String text = source.substring(start, current);
		if (isReal) {
			addToken(REAL, Double.parseDouble(text));
		} else {
			addToken(ENTERO, Integer.parseInt(text));
		}
	}

	private void string() {
		while (peek() != '"' && !isAtEnd()) {
			if (peek() == '\n') {
				addToken(ERROR, "String con mala sintaxis");
				return;
			}
			advance();
		}

		if (isAtEnd()) {
			addToken(ERROR, "String sin cerrar");
			return;
		}

		advance(); // Closing "
		String value = source.substring(start + 1, current - 1);
		addToken(STRING, value);
	}

	private boolean checkKeyword(String expected) {
		int length = expected.length();
		if (current - 1 + length > source.length()) return false;

		String text = source.substring(current - 1, current - 1 + length);
		if (text.equals(expected)) {
			current = current - 1 + length;
			return true;
		}
		return false;
	}

	// Utility methods
	private boolean isAtEnd() {
		return current >= source.length();
	}

	private boolean hasNext() {
		return current < source.length();
	}

	private char advance() {
		return source.charAt(current++);
	}

	private boolean match(char expected) {
		if (isAtEnd() || source.charAt(current) != expected) return false;
		current++;
		return true;
	}

	private char peek() {
		return isAtEnd() ? '\0' : source.charAt(current);
	}

	private char peekNext() {
		return (current + 1 >= source.length()) ? '\0' : source.charAt(current + 1);
	}

	private boolean isAlpha(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
	}

	private boolean isAlphaNumeric(char c) {
		return isAlpha(c) || isDigit(c);
	}

	private boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private void addToken(TokenType type) {
		addToken(type, null);
	}

	private void addToken(TokenType type, Object literal) {
		String text = source.substring(start, current);
		tokens.add(new Token(type, text, literal, line));
	}

	private static Map<String, TokenType> initKeywords() {
		Map<String, TokenType> kw = new HashMap<>();
		kw.put("principalsito", PRINCIPALSITO);
		kw.put("porfavor", PORFAVOR);
		kw.put("favor", FAVOR);
		kw.put("podriasCrear", PODRIASCREAR);
		kw.put("metodillo", METODILLO);
		kw.put("podriasImprimir", PODRIASIMPRIMIR);
		kw.put("podriasLeer", PODRIASLEER);
		kw.put("aclama", ACLAMA);
		kw.put("siCumple", SICUMPLE);
		kw.put("peroSiCumple", PEROSICUMPLE);
		kw.put("casoContrario", CASOCONTRARIO);
		kw.put("siControla", SICONTROLA);
		kw.put("SiPersiste", SIPERSISTE);
		kw.put("saltear", SALTEAR);
		kw.put("parar", PARAR);
		kw.put("enCasoSea", ENCASOSEA);
		kw.put("oSino", OSINO);
		kw.put("retorna", RETORNA);
		kw.put("clona", CLONA);
		kw.put("enterito", ENTERITO);
		kw.put("realito", REALITO);
		kw.put("booleanito", BOOLEANITO);
		kw.put("charsito", CHARSITO);
		kw.put("cadenita", CADENITA);
		kw.put("vacio", VACIO);
		kw.put("constantito", CONSTANTITO);
		kw.put("clasesita", CLASESITA);
		kw.put("true", BOOLEAN);
		kw.put("false", BOOLEAN);
		kw.put("yo", YO);
		kw.put("invoco", INVOCO);
		return kw;
	}
}