package lexico;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class AnalizadorSintactico {
    private static final String SEPARATOR = "=".repeat(140);
    private static final String[] CSV_PATHS = {
            "TABLA_TAS_limpia_final.xlsx",
    };
    private static boolean hadError = false;

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            System.out.println("Uso: java AnalizadorCompleto [archivo]");
            System.exit(64);
        } else if (args.length == 1) {
            runFile(args[0]);
        } else {
            runPrompt();
        }
    }

    private static void runFile(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        run(new String(bytes, Charset.defaultCharset()));
        if (hadError) System.exit(65);
    }

    private static void runPrompt() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            StringBuilder code = new StringBuilder();

            printHeader();

            while (true) {
                String line = reader.readLine();

                if (line == null) break;

                if (line.trim().equals("#")) {
                    if (code.length() > 0) {
                        run(code.toString());
                        code.setLength(0);
                    } else {
                        System.out.println("- No hay código que analizar");
                    }

                    System.out.println("\n¿Desea analizar otro código? (s/n)");
                    String respuesta = reader.readLine();
                    if (respuesta == null || !respuesta.toLowerCase().startsWith("s")) {
                        System.out.println("\n¡Hasta luego!");
                        break;
                    }
                    System.out.println("\nEscriba su código fuente:");
                } else {
                    code.append(line).append("\n");
                }
            }
        }
    }

    private static void printHeader() {
        System.out.println("╔════════════════════════════════════════════════════╗");
        System.out.println("║    ANALIZADOR LÉXICO - SINTÁCTICO - SEMANTICO      ║");
        System.out.println("╚════════════════════════════════════════════════════╝");
        System.out.println("\nEscriba su código fuente.");
        System.out.println("Termine escribiendo '#' en una línea sola.\n");
    }

    private static void run(String source) {
        // ANÁLISIS LÉXICO
        System.out.println("\n" + SEPARATOR);
        System.out.println("                                                        ANÁLISIS LÉXICO");
        System.out.println(SEPARATOR);

        Scanner scanner = new Scanner(source);
        List<Token> tokens = scanner.scanTokens();

        printTokens(tokens);

        if (hasLexicalErrors(tokens)) {
            System.out.println("\n- Se encontraron errores léxicos. No se puede continuar.");
            hadError = true;
            return;
        }

        System.out.println("\n- Análisis léxico completado exitosamente.");
        System.out.println("Total de tokens: " + tokens.size());

        // ANÁLISIS SINTÁCTICO
        performSyntaxAnalysis(tokens);
    }

    private static void printTokens(List<Token> tokens) {
        System.out.println();
        System.out.printf("%-35s | %-35s | %-35s | %-35s%n", "Token", "Lexema", "Literal", "Categoría");
        System.out.println("-".repeat(140));
        tokens.forEach(System.out::println);
    }

    private static boolean hasLexicalErrors(List<Token> tokens) {
        return tokens.stream().anyMatch(t -> t.type == TokenType.ERROR);
    }

    private static void performSyntaxAnalysis(List<Token> tokens) {
        try {
            Parser parser = new Parser();
            System.out.println("\nCargando tabla de análisis sintáctico...");

            if (!loadParserTable(parser)) {
                System.err.println("\n✗ No se pudo cargar el archivo CSV.");
                promptManualPath(parser);
            }

            parser.analizar(tokens);

        } catch (IOException e) {
            System.err.println("✗ Error al cargar la tabla TAS: " + e.getMessage());
            System.err.println("Directorio de trabajo actual: " + System.getProperty("user.dir"));
            hadError = true;
        } catch (Exception e) {
            System.err.println("✗ Error durante el análisis sintáctico: " + e.getMessage());
            e.printStackTrace();
            hadError = true;
        }
    }

    private static boolean loadParserTable(Parser parser) {
        for (String path : CSV_PATHS) {
            try {
                parser.cargarTAS(path);
                return true;
            } catch (IOException e) {
                // Try next path
            }
        }
        return false;
    }

    private static void promptManualPath(Parser parser) throws IOException {
        System.err.println("Por favor, ingrese la ruta completa del archivo:");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String rutaManual = reader.readLine();
        parser.cargarTAS(rutaManual);
    }
}