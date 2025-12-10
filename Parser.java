package lexico;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Parser {
    private static final String[] EPSILON_VALUES = {"ε", "λ", "&"};
    private static final String[] POSSIBLE_PATHS = {"TABLA_TAS_limpia_final.xlsx"};

    private final Map<String, Map<String, String>> TAS = new HashMap<>();
    private final Set<String> noTerminales = new HashSet<>();
    private final Stack<String> pila = new Stack<>();
    private final Map<TokenType, String> tokenToTerminal = new HashMap<>();

    private List<Token> tokens;
    private int currentTokenIndex;

    // =============================================================================
    // TABLA ÚNICA DE SÍMBOLOS
    // =============================================================================

    static class IdentificadorInfo {
        String nombre;
        String tipo;
        Object valor;
        String modificador;
        boolean inicializada;
        int linea;
        String scope;

        List<String> tiposParametros;
        List<String> nombresParametros;

        IdentificadorInfo(String nombre, String tipo, Object valor, String modificador, int linea, String scope) {
            this.nombre = nombre;
            this.tipo = tipo;
            this.valor = valor;
            this.modificador = modificador;
            this.inicializada = false;
            this.linea = linea;
            this.scope = scope;
            this.tiposParametros = new ArrayList<>();
            this.nombresParametros = new ArrayList<>();
        }
    }

    static class ParametroInfo {
        String tipo;
        String nombre;

        ParametroInfo(String tipo, String nombre) {
            this.tipo = tipo;
            this.nombre = nombre;
        }
    }

    private Map<String, IdentificadorInfo> tablaSimbolos = new HashMap<>();
    private List<String> erroresSemanticos = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private Set<String> erroresReportados = new HashSet<>();

    // =============================================================================
    // CONTROL DE CONTEXTO
    // =============================================================================

    private String tipoActual = "";
    private String idPendiente = "";
    private boolean esConstanteActual = false;
    private String funcionActual = "";
    private String claseActual = "";
    private int nivelBloque = 0;
    private int nivelBloqueFuncion = 0;
    private int nivelBucle = 0;
    private boolean dentroDeSwitch = false;
    private String tipoSwitch = "";  // Tipo del primer case
    private Set<Object> valoresCaseVistos = new HashSet<>();  // Valores ya usados
    private int lineaPrimerCase = -1;
    private boolean esperandoTipoRetorno = false;
    private boolean dentroDeFuncion = false;
    private boolean acabaDeVerAclama = false;
    private boolean vieneDeAclama = false;
    private boolean acabaDeVerInvoco = false;
    private String objetoInvocando = "";
    private boolean dentroDeConstructor = false;
    private boolean dentroDeMetodo = false;

    // Control de arreglos constantito
    private int tamanoArregloDeclarado = -1;
    private List<String> elementosArreglo = new ArrayList<>();
    private boolean dentroDeInicializacionArreglo = false;
    private String tipoArregloActual = "";

    private List<ParametroInfo> parametrosActuales = new ArrayList<>();
    private String tipoRetornoActual = "";
    private boolean funcionTieneRetorno = false;

    private String nombreVarAsignando = "";
    private IdentificadorInfo varAsignando = null;

    // Evaluador de expresiones mejorado
    private boolean dentroDeExpresion = false;
    private List<Object> expresionTokens = new ArrayList<>();  // Almacena tokens de la expresión
    private String tipoExpresionActual = "";

    private int lineaSwitch = -1;  // Línea donde empieza el switch
    private boolean capturandoExpresionSwitch = false;
    private List<Token> tokensExpresionSwitch = new ArrayList<>();



    public Parser() {
        inicializarMapeoTokens();
    }

    // =============================================================================
    // EVALUADOR DE EXPRESIONES CON PRECEDENCIA
    // =============================================================================

    private static class ExpresionResult {
        float valor;
        boolean valorBooleano;
        boolean esReal;
        boolean esBooleano;
        boolean esString;      // NUEVO
        boolean esChar;        // NUEVO
        String tipo;

        ExpresionResult(float valor, boolean esReal, String tipo) {
            this.valor = valor;
            this.valorBooleano = false;
            this.esReal = esReal;
            this.esBooleano = false;
            this.esString = false;
            this.esChar = false;
            this.tipo = tipo;
        }

        // Constructor para booleanos
        ExpresionResult(boolean valorBooleano, String tipo) {
            this.valor = 0;
            this.valorBooleano = valorBooleano;
            this.esReal = false;
            this.esBooleano = true;
            this.esString = false;
            this.esChar = false;
            this.tipo = tipo;
        }

        // NUEVO: Constructor para strings y chars
        ExpresionResult(String tipo, boolean esString, boolean esChar) {
            this.valor = 0;
            this.valorBooleano = false;
            this.esReal = false;
            this.esBooleano = false;
            this.esString = esString;
            this.esChar = esChar;
            this.tipo = tipo;
        }
    }

    private ExpresionResult evaluarExpresion(List<Object> tokens, int lineaActual) {
        if (tokens.isEmpty()) {
            return new ExpresionResult(0, false, "enterito");
        }

        // Convertir tokens a notación postfija (Shunting Yard)
        List<Object> postfix = convertirAPostfija(tokens);

        // Debug: imprimir expresión postfija
        System.out.print("\n   >>> [DEBUG] Tokens de expresión: ");
        for (Object t : tokens) {
            System.out.print(t + " ");
        }
        System.out.print("\n   >>> [DEBUG] Postfija: ");
        for (Object t : postfix) {
            System.out.print(t + " ");
        }
        System.out.println();

        // Evaluar expresión postfija (AHORA SÍ PASAMOS lineaActual)
        return evaluarPostfija(postfix, lineaActual);
    }

    // ==================== DESPUÉS DEL MÉTODO evaluarExpresion() ====================

    private void validarCondicionBooleana(List<Token> tokenList, int lineNumber, String contexto) {
        if (tokenList == null || tokenList.isEmpty()) {
            erroresSemanticos.add("❌ ERROR: Condición vacía en " + contexto + " (línea " + lineNumber + ")");
            return;
        }

        System.out.println("\n   >>> [DEBUG] Validando condición en " + contexto);

        // Convertir List<Token> a List<Object> para evaluarExpresion
        List<Object> expresionTokens = new ArrayList<>();

        for (Token t : tokenList) {
            if (t.type == TokenType.ENTERO) {
                expresionTokens.add(t.literal);
            } else if (t.type == TokenType.REAL) {
                expresionTokens.add(((Number) t.literal).floatValue());
            } else if (t.type == TokenType.BOOLEAN) {
                // CORREGIDO: Crear un marcador especial para booleanos
                boolean valorBool = t.lexeme.equals("true");
                expresionTokens.add("BOOL:" + valorBool);  // Marcador especial
            } else if (t.type == TokenType.STRING) {
                // CORREGIDO: Crear un marcador especial para strings
                expresionTokens.add("STR:" + t.lexeme);  // Marcador especial
            } else if (t.type == TokenType.CHAR) {
                expresionTokens.add("CHAR:" + t.literal);  // Marcador especial
            } else if (t.type == TokenType.IDENTIFICADOR) {
                expresionTokens.add(t.lexeme);
            } else if (t.type == TokenType.SUMA) {
                expresionTokens.add("+");
            } else if (t.type == TokenType.MENOS) {
                expresionTokens.add("-");
            } else if (t.type == TokenType.ASTERISCO) {
                expresionTokens.add("*");
            } else if (t.type == TokenType.DIVISION) {
                expresionTokens.add("/");
            } else if (t.type == TokenType.MENOR) {
                expresionTokens.add("<");
            } else if (t.type == TokenType.MAYOR) {
                expresionTokens.add(">");
            } else if (t.type == TokenType.MENOR_QUE) {
                expresionTokens.add("<=");
            } else if (t.type == TokenType.MAYOR_QUE) {
                expresionTokens.add(">=");
            } else if (t.type == TokenType.EQUIVALE) {
                expresionTokens.add("==");
            } else if (t.type == TokenType.DIFERENTE) {
                expresionTokens.add("!=");
            } else if (t.type == TokenType.AND) {
                expresionTokens.add("&&");
            } else if (t.type == TokenType.OR) {
                expresionTokens.add("||");
            } else if (t.type == TokenType.PAREN_IZQ) {
                expresionTokens.add("(");
            } else if (t.type == TokenType.PAREN_DER) {
                expresionTokens.add(")");
            }
        }

        ExpresionResult resultado = evaluarExpresion(expresionTokens, lineNumber);

        if (!resultado.tipo.equals("booleanito")) {
            erroresSemanticos.add("❌ ERROR: La condición en " + contexto +
                    " no contiene un tipo de dato booleanito (línea " + lineNumber + ")");
        }
    }

    private List<Object> convertirAPostfija(List<Object> infix) {
        List<Object> output = new ArrayList<>();
        Stack<String> operators = new Stack<>();

        for (Object token : infix) {
            if (token instanceof Number) {
                // Número directo a la salida
                output.add(token);
            } else if (token instanceof String) {
                String str = (String) token;


                if (str.equals("(")) {
                    operators.push(str);
                } else if (str.equals(")")) {
                    // Desapilar hasta encontrar (
                    while (!operators.isEmpty() && !operators.peek().equals("(")) {
                        output.add(operators.pop());
                    }
                    if (!operators.isEmpty()) {
                        operators.pop(); // Quitar el (
                    }
                } else if (esOperador(str)) {
                    // Es operador aritmético
                    while (!operators.isEmpty() &&
                            !operators.peek().equals("(") &&
                            precedencia(operators.peek()) >= precedencia(str)) {
                        output.add(operators.pop());
                    }
                    operators.push(str);
                } else {
                    // Es variable o identificador
                    output.add(token);
                }
            }
        }

        // Vaciar operadores restantes
        while (!operators.isEmpty()) {
            String op = operators.pop();
            if (!op.equals("(") && !op.equals(")")) {
                output.add(op);
            }
        }

        return output;
    }

    private ExpresionResult evaluarPostfija(List<Object> postfix, int lineaActual) {
        Stack<ExpresionResult> stack = new Stack<>();
        boolean tieneReales = false;
        String tipoDetectado = "enterito";

        for (Object token : postfix) {
            if (token instanceof Integer) {
                stack.push(new ExpresionResult(((Integer) token).floatValue(), false, "enterito"));
            } else if (token instanceof Float || token instanceof Double) {
                float valor = ((Number) token).floatValue();
                stack.push(new ExpresionResult(valor, true, "realito"));
                tieneReales = true;
                tipoDetectado = "realito";
            } else if (token instanceof String) {
                String str = (String) token;

                if (str.startsWith("BOOL:")) {
                    boolean valor = str.substring(5).equals("true");
                    stack.push(new ExpresionResult(valor, "booleanito"));
                    tipoDetectado = "booleanito";
                    continue;
                } else if (str.startsWith("STR:")) {
                    // CORREGIDO: Usa el nuevo constructor
                    stack.push(new ExpresionResult("cadenita", true, false));
                    tipoDetectado = "cadenita";
                    continue;
                } else if (str.startsWith("CHAR:")) {
                    // CORREGIDO: Usa el nuevo constructor
                    stack.push(new ExpresionResult("charsito", false, true));
                    tipoDetectado = "charsito";
                    continue;
                }

                // OPERADORES ARITMÉTICOS
                // OPERADORES ARITMÉTICOS
                if (esOperadorAritmetico(str)) {
                    if (stack.size() < 2) {
                        System.out.println("\n   >>> [ERROR DEBUG] Pila insuficiente para operador: " + str);
                        continue;
                    }

                    ExpresionResult b = stack.pop();
                    ExpresionResult a = stack.pop();

                    // ========== VALIDACIÓN CRÍTICA: OPERADORES ARITMÉTICOS SOLO ENTRE NÚMEROS ==========
                    boolean aEsNumero = (a.tipo.equals("enterito") || a.tipo.equals("realito"));
                    boolean bEsNumero = (b.tipo.equals("enterito") || b.tipo.equals("realito"));

                    if (!aEsNumero || !bEsNumero) {
                        // ERROR: Intentando usar operadores aritméticos con tipos no numéricos
                        if (!aEsNumero) {
                            erroresSemanticos.add("❌ ERROR: No se puede usar el operador aritmético '" + str +
                                    "' con tipo " + a.tipo.toUpperCase() + " (línea " + lineaActual + ")");
                        }
                        if (!bEsNumero) {
                            erroresSemanticos.add("❌ ERROR: No se puede usar el operador aritmético '" + str +
                                    "' con tipo " + b.tipo.toUpperCase() + " (línea " + lineaActual + ")");
                        }

                        // Mantener un resultado de error pero con tipo realito para evitar confusión
                        stack.push(new ExpresionResult(0.0f, true, "realito"));
                        tieneReales = true;
                        tipoDetectado = "realito";
                        continue;
                    }
                    // ==================================================================================

                    // Si ambos son números, proceder normalmente
                    if (b.esReal || a.esReal) {
                        tieneReales = true;
                        tipoDetectado = "realito";
                    }

                    float resultado = aplicarOperacion(a.valor, b.valor, str);
                    System.out.println("   >>> [DEBUG] " + a.valor + " " + str + " " + b.valor + " = " + resultado);
                    stack.push(new ExpresionResult(resultado, tieneReales, tipoDetectado));
                }
                // Operadores de comparación
                else if (esOperadorComparacion(str)) {
                    if (stack.size() < 2) {
                        System.out.println("\n   >>> [ERROR DEBUG] Pila insuficiente para operador: " + str);
                        continue;
                    }

                    ExpresionResult b = stack.pop();
                    ExpresionResult a = stack.pop();

                    boolean comparacionValida = false;

                    // Números se pueden comparar entre sí (enterito con realito)
                    if ((a.tipo.equals("enterito") || a.tipo.equals("realito")) &&
                            (b.tipo.equals("enterito") || b.tipo.equals("realito"))) {
                        comparacionValida = true;
                    }
                    // Booleans solo con booleans (SOLO == y !=)
                    else if (a.tipo.equals("booleanito") && b.tipo.equals("booleanito")) {
                        if (str.equals("==") || str.equals("!=")) {
                            comparacionValida = true;
                        }
                    }
                    // Cadenas solo con cadenas (SOLO == y !=)
                    else if (a.tipo.equals("cadenita") && b.tipo.equals("cadenita")) {
                        if (str.equals("==") || str.equals("!=")) {
                            comparacionValida = true;
                        }
                    }
                    // Chars solo con chars
                    else if (a.tipo.equals("charsito") && b.tipo.equals("charsito")) {
                        comparacionValida = true;
                    }

                    if (!comparacionValida) {
                        erroresSemanticos.add("❌ ERROR: No se pueden comparar " +
                                a.tipo.toUpperCase() + " con " + b.tipo.toUpperCase() +
                                " usando el operador '" + str + "' (línea " + lineaActual + ")");
                        stack.push(new ExpresionResult(false, "booleanito"));
                        continue;
                    }

                    boolean resultadoComparacion = aplicarComparacion(a.valor, b.valor, str);
                    System.out.println("   >>> [DEBUG] " + a.valor + " " + str + " " + b.valor + " = " + resultadoComparacion);

                    stack.push(new ExpresionResult(resultadoComparacion, "booleanito"));
                }
                // Operadores lógicos
                else if (esOperadorLogico(str)) {
                    if (stack.size() < 2) {
                        System.out.println("\n   >>> [ERROR DEBUG] Pila insuficiente para operador: " + str);
                        continue;
                    }

                    ExpresionResult b = stack.pop();
                    ExpresionResult a = stack.pop();

                    // ========== VALIDACIÓN: OPERADORES LÓGICOS SOLO ENTRE BOOLEANOS ==========
                    if (!a.tipo.equals("booleanito") || !b.tipo.equals("booleanito")) {
                        if (!a.tipo.equals("booleanito")) {
                            erroresSemanticos.add("❌ ERROR: No se puede usar el operador lógico '" + str +
                                    "' con tipo " + a.tipo.toUpperCase() + " (línea " + lineaActual + ")");
                        }
                        if (!b.tipo.equals("booleanito")) {
                            erroresSemanticos.add("❌ ERROR: No se puede usar el operador lógico '" + str +
                                    "' con tipo " + b.tipo.toUpperCase() + " (línea " + lineaActual + ")");
                        }
                        stack.push(new ExpresionResult(false, "booleanito"));
                        continue;
                    }
                    // ========================================================================

                    boolean resultado = aplicarOperadorLogico(a.valorBooleano, b.valorBooleano, str);
                    System.out.println("   >>> [DEBUG] " + a.valorBooleano + " " + str + " " + b.valorBooleano + " = " + resultado);
                    stack.push(new ExpresionResult(resultado, "booleanito"));
                }
                // Variable
                else {
                    String scope = obtenerScopeActual();
                    IdentificadorInfo var = buscarIdentificador(scope + "." + str);
                    if (var == null) var = buscarIdentificador("global." + str);

                    if (var != null) {
                        String tipoVar = var.tipo;

                        // Actualizar tipo detectado según prioridad
                        if (tipoVar.equals("booleanito")) {
                            tipoDetectado = "booleanito";
                        } else if (tipoVar.equals("cadenita") && !tipoDetectado.equals("booleanito")) {
                            tipoDetectado = "cadenita";
                        } else if (tipoVar.equals("charsito") && !tipoDetectado.equals("booleanito") && !tipoDetectado.equals("cadenita")) {
                            tipoDetectado = "charsito";
                        } else if (tipoVar.equals("realito") && tipoDetectado.equals("enterito")) {
                            tieneReales = true;
                            tipoDetectado = "realito";
                        }

                        // Agregar al stack
                        if (var.valor instanceof Number) {
                            float valor = ((Number) var.valor).floatValue();
                            boolean esReal = tipoVar.equals("realito");
                            stack.push(new ExpresionResult(valor, esReal, tipoVar));
                        } else if (var.valor instanceof Boolean) {
                            stack.push(new ExpresionResult((Boolean) var.valor, tipoVar));
                        } else if (tipoVar.equals("cadenita")) {
                            // Es string
                            stack.push(new ExpresionResult("cadenita", true, false));
                        } else if (tipoVar.equals("charsito")) {
                            // Es char
                            stack.push(new ExpresionResult("charsito", false, true));
                        } else {
                            // Otros tipos
                            stack.push(new ExpresionResult(0, false, tipoVar));
                        }
                    } else {
                        stack.push(new ExpresionResult(0, false, "enterito"));
                    }
                }
            }
        }

        if (stack.isEmpty()) {
            return new ExpresionResult(0, false, "enterito");
        }

        ExpresionResult resultado = stack.pop();

        // IMPORTANTE: No sobrescribir el tipo si ya es correcto
        if (resultado.esBooleano) {
            // Mantener como booleanito
        } else if (resultado.esString) {
            resultado.tipo = "cadenita";
        } else if (resultado.esChar) {
            resultado.tipo = "charsito";
        } else {
            // Solo para números
            resultado.esReal = tieneReales;
            if (!resultado.tipo.equals("booleanito") &&
                    !resultado.tipo.equals("cadenita") &&
                    !resultado.tipo.equals("charsito")) {
                resultado.tipo = tipoDetectado;
            }
        }

        return resultado;
    }

    private boolean esOperador(String str) {
        return esOperadorAritmetico(str) || esOperadorComparacion(str) || esOperadorLogico(str);
    }

    private boolean esOperadorAritmetico(String str) {
        return str.equals("+") || str.equals("-") || str.equals("*") || str.equals("/");
    }

    private boolean esOperadorComparacion(String str) {
        return str.equals("<") || str.equals(">") ||
                str.equals("<=") || str.equals(">=") ||
                str.equals("==") || str.equals("!=");
    }

    private boolean esOperadorLogico(String str) {
        return str.equals("&&") || str.equals("||");
    }

    private boolean aplicarComparacion(float a, float b, String operador) {
        switch (operador) {
            case "<":  return a < b;
            case ">":  return a > b;
            case "<=": return a <= b;
            case ">=": return a >= b;
            case "==": return a == b;
            case "!=": return a != b;
            default: return false;
        }
    }

    private boolean aplicarOperadorLogico(boolean a, boolean b, String operador) {
        switch (operador) {
            case "&&": return a && b;
            case "||": return a || b;
            default: return false;
        }
    }

    private int precedencia(String op) {
        switch (op) {
            case "||":
                return 1;
            case "&&":
                return 2;
            case "==":
            case "!=":
                return 3;
            case "<":
            case ">":
            case "<=":
            case ">=":
                return 4;
            case "+":
            case "-":
                return 5;
            case "*":
            case "/":
                return 6;
            default:
                return 0;
        }
    }

    private float aplicarOperacion(float a, float b, String operador) {
        switch (operador) {
            case "+": return a + b;
            case "-": return a - b;
            case "*": return a * b;
            case "/": return b != 0 ? a / b : 0;
            default: return 0;
        }
    }

    // =============================================================================
    // INICIALIZACIÓN
    // =============================================================================

    private void inicializarMapeoTokens() {
        tokenToTerminal.put(TokenType.PRINCIPALSITO, "principalsito");
        tokenToTerminal.put(TokenType.FAVOR, "favor");
        tokenToTerminal.put(TokenType.PORFAVOR, "porfavor");
        tokenToTerminal.put(TokenType.PODRIASCREAR, "podriasCrear");
        tokenToTerminal.put(TokenType.PODRIASIMPRIMIR, "podriasImprimir");
        tokenToTerminal.put(TokenType.PODRIASLEER, "podriasLeer");
        tokenToTerminal.put(TokenType.CLASESITA, "clasesita");
        tokenToTerminal.put(TokenType.METODILLO, "metodillo");
        tokenToTerminal.put(TokenType.ACLAMA, "aclama");
        tokenToTerminal.put(TokenType.INVOCO, "invoco");
        tokenToTerminal.put(TokenType.CLONA, "clona");
        tokenToTerminal.put(TokenType.YO, "yo");
        tokenToTerminal.put(TokenType.SICUMPLE, "siCumple");
        tokenToTerminal.put(TokenType.PEROSICUMPLE, "peroSiCumple");
        tokenToTerminal.put(TokenType.CASOCONTRARIO, "casoContrario");
        tokenToTerminal.put(TokenType.SICONTROLA, "siControla");
        tokenToTerminal.put(TokenType.SIPERSISTE, "SiPersiste");
        tokenToTerminal.put(TokenType.ENCASOSEA, "enCasoSea");
        tokenToTerminal.put(TokenType.OSINO, "oSino");
        tokenToTerminal.put(TokenType.SALTEAR, "saltear");
        tokenToTerminal.put(TokenType.PARAR, "parar");
        tokenToTerminal.put(TokenType.RETORNA, "retorna");
        tokenToTerminal.put(TokenType.ENTERITO, "enterito");
        tokenToTerminal.put(TokenType.REALITO, "realito");
        tokenToTerminal.put(TokenType.BOOLEANITO, "booleanito");
        tokenToTerminal.put(TokenType.VACIO, "vacio");
        tokenToTerminal.put(TokenType.CHARSITO, "charsito");
        tokenToTerminal.put(TokenType.CADENITA, "cadenita");
        tokenToTerminal.put(TokenType.ARREGLITO, "arreglito");
        tokenToTerminal.put(TokenType.CONSTANTITO, "constantito");

        tokenToTerminal.put(TokenType.IDENTIFICADOR, "id");
        tokenToTerminal.put(TokenType.IDENTIFICADOR_MAYUSCULA, "id");
        tokenToTerminal.put(TokenType.ENTERO, "entero");
        tokenToTerminal.put(TokenType.REAL, "decimal");
        tokenToTerminal.put(TokenType.STRING, "cadena");
        tokenToTerminal.put(TokenType.CHAR, "char");
        tokenToTerminal.put(TokenType.BOOLEAN, "TRUE");

        tokenToTerminal.put(TokenType.PAREN_IZQ, "(");
        tokenToTerminal.put(TokenType.PAREN_DER, ")");
        tokenToTerminal.put(TokenType.LLAVE_IZQ, "{");
        tokenToTerminal.put(TokenType.LLAVE_DER, "}");
        tokenToTerminal.put(TokenType.CORCHETE_IZQ, "[");
        tokenToTerminal.put(TokenType.CORCHETE_DER, "]");
        tokenToTerminal.put(TokenType.DOS_PUNTOS, ":");
        tokenToTerminal.put(TokenType.SONRISA, ":)");
        tokenToTerminal.put(TokenType.IGUAL, "=");
        tokenToTerminal.put(TokenType.EQUIVALE, "==");
        tokenToTerminal.put(TokenType.DIFERENTE, "!=");
        tokenToTerminal.put(TokenType.MENOR, "<");
        tokenToTerminal.put(TokenType.MAYOR, ">");
        tokenToTerminal.put(TokenType.MENOR_QUE, "<=");
        tokenToTerminal.put(TokenType.MAYOR_QUE, ">=");
        tokenToTerminal.put(TokenType.SUMA, "+");
        tokenToTerminal.put(TokenType.MENOS, "-");
        tokenToTerminal.put(TokenType.ASTERISCO, "*");
        tokenToTerminal.put(TokenType.DIVISION, "/");
        tokenToTerminal.put(TokenType.AND, "&&");
        tokenToTerminal.put(TokenType.OR, "||");
        tokenToTerminal.put(TokenType.PUNTO, ".");
        tokenToTerminal.put(TokenType.COMA, ",");
    }

    // =============================================================================
    // CARGA DE TABLA TAS
    // =============================================================================

    public void cargarTAS(String archivo) throws IOException {
        FileInputStream fis = tryOpenFile();

        try (ZipInputStream zipIn = new ZipInputStream(fis)) {
            Document sheetDoc = null;
            Document stringsDoc = null;

            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheetDoc = parseXML(zipIn);
                } else if (entry.getName().equals("xl/sharedStrings.xml")) {
                    stringsDoc = parseXML(zipIn);
                }
                zipIn.closeEntry();
            }

            if (sheetDoc == null) {
                throw new IOException("No se encontró sheet1.xml");
            }

            List<String> sharedStrings = extractSharedStrings(stringsDoc);
            procesarFilasXLSX(sheetDoc, sharedStrings);

        } catch (Exception e) {
            throw new IOException("Error al procesar XLSX: " + e.getMessage(), e);
        }
    }

    private FileInputStream tryOpenFile() throws IOException {
        IOException lastException = null;
        for (String ruta : POSSIBLE_PATHS) {
            try {
                return new FileInputStream(ruta);
            } catch (IOException e) {
                lastException = e;
            }
        }
        System.err.println("✗ No se pudo encontrar el archivo XLSX");
        throw lastException;
    }

    private List<String> extractSharedStrings(Document stringsDoc) {
        List<String> strings = new ArrayList<>();
        if (stringsDoc != null) {
            NodeList siNodes = stringsDoc.getElementsByTagName("si");
            for (int i = 0; i < siNodes.getLength(); i++) {
                Element si = (Element) siNodes.item(i);
                NodeList tNodes = si.getElementsByTagName("t");
                strings.add(tNodes.getLength() > 0 ? tNodes.item(0).getTextContent() : "");
            }
        }
        return strings;
    }

    private Document parseXML(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) > -1) {
            baos.write(buffer, 0, len);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(baos.toByteArray()));
    }

    private void procesarFilasXLSX(Document doc, List<String> sharedStrings) {
        NodeList rows = doc.getElementsByTagName("row");
        String[] encabezados = null;

        for (int r = 0; r < rows.getLength(); r++) {
            Element row = (Element) rows.item(r);
            int rowNum = Integer.parseInt(row.getAttribute("r"));

            if (rowNum <= 2) continue;

            String[] datos = extraerFila(row, sharedStrings);

            if (rowNum == 3) {
                encabezados = datos;
                continue;
            }

            if (datos.length < 2 || datos[1].trim().isEmpty()) continue;

            String noTerminal = datos[1].trim();
            noTerminales.add(noTerminal);

            Map<String, String> producciones = new HashMap<>();
            for (int i = 2; i < datos.length && encabezados != null && i < encabezados.length; i++) {
                String produccion = datos[i].trim();
                String terminal = encabezados[i].trim();

                if (!produccion.isEmpty() && !produccion.equals("?")) {
                    producciones.put(terminal, produccion);
                } else if (isEpsilon(produccion)) {
                    producciones.put(terminal, "λ");
                }
            }

            TAS.put(noTerminal, producciones);
        }
    }

    private String[] extraerFila(Element row, List<String> sharedStrings) {
        List<String> valores = new ArrayList<>();
        NodeList cells = row.getElementsByTagName("c");

        int colIndex = 0;
        for (int c = 0; c < cells.getLength(); c++) {
            Element cell = (Element) cells.item(c);
            String cellRef = cell.getAttribute("r");
            int targetCol = getColumnIndex(cellRef);

            while (colIndex < targetCol) {
                valores.add("");
                colIndex++;
            }

            String valor = extractCellValue(cell, sharedStrings);
            valores.add(valor);
            colIndex++;
        }

        return valores.toArray(new String[0]);
    }

    private String extractCellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        NodeList vNodes = cell.getElementsByTagName("v");

        if (vNodes.getLength() == 0) return "";

        String v = vNodes.item(0).getTextContent();

        if ("s".equals(type)) {
            int index = Integer.parseInt(v);
            return index < sharedStrings.size() ? sharedStrings.get(index) : "";
        }

        return v;
    }

    private int getColumnIndex(String cellRef) {
        String col = cellRef.replaceAll("[0-9]", "");
        int index = 0;
        for (char c : col.toCharArray()) {
            index = index * 26 + (c - 'A' + 1);
        }
        return index - 1;
    }

    private boolean isEpsilon(String str) {
        for (String eps : EPSILON_VALUES) {
            if (str.equals(eps)) return true;
        }
        return false;
    }

    // =============================================================================
    // ANÁLISIS SINTÁCTICO
    // =============================================================================

    public void analizar(List<Token> tokensEntrada) {
        this.tokens = tokensEntrada;
        this.currentTokenIndex = 0;

        tablaSimbolos.clear();
        erroresSemanticos.clear();
        warnings.clear();
        erroresReportados.clear();
        resetearContexto();

        pila.clear();
        pila.push("$");
        pila.push("S");

        System.out.println("═".repeat(140));
        System.out.printf("\033[1m%-35s %-70s %-35s%n\033[0m", "PILA", "ENTRADA", "ACCIÓN");
        System.out.println("═".repeat(140));

        while (!pila.isEmpty()) {
            String tope = pila.peek();
            String terminalActual = obtenerTerminalActual();

            System.out.printf("%-35s %-70s ", mostrarPila(), obtenerEntradaRestante());

            if (tope.equals("$") && terminalActual.equals("$")) {
                System.out.println("\n\nANÁLISIS SINTÁCTICO CORRECTO");
                imprimirReporteSemantico();
                return;
            }

            if (!noTerminales.contains(tope)) {
                if (tope.equals(terminalActual)) {
                    System.out.println();
                    Token tokenActual = currentTokenIndex < tokens.size() ? tokens.get(currentTokenIndex) : null;
                    procesarMatchTerminal(tope, tokenActual);
                    pila.pop();
                    avanzarToken();
                } else {
                    System.out.println("\n\nerror de sintaxis");
                    return;
                }
            } else {
                String produccion = buscarProduccion(tope, terminalActual);

                if (produccion != null) {
                    String display = isEpsilon(produccion) ? "&" : produccion;
                    System.out.printf("\033[1m%s -> %s%n\033[0m", tope, display);

                    procesarDerivacion(tope, produccion);

                    pila.pop();
                    empilar(produccion);
                } else {
                    System.out.println("\n\nerror de sintaxis");
                    return;
                }
            }
        }

        System.out.println("\nANÁLISIS SINTÁCTICO CORRECTO");
        imprimirReporteSemantico();
    }

    private void resetearContexto() {
        tipoActual = "";
        esConstanteActual = false;
        idPendiente = "";
        funcionActual = "";
        claseActual = "";
        nivelBloque = 0;
        nivelBloqueFuncion = 0;
        nivelBucle = 0;
        dentroDeSwitch = false;
        parametrosActuales.clear();
        tipoRetornoActual = "";
        funcionTieneRetorno = false;
        esperandoTipoRetorno = false;
        dentroDeFuncion = false;
        acabaDeVerAclama = false;
        vieneDeAclama = false;
        acabaDeVerInvoco = false;
        objetoInvocando = "";
        dentroDeConstructor = false;
        dentroDeExpresion = false;
        expresionTokens.clear();
        tipoExpresionActual = "";
        varAsignando = null;
        nombreVarAsignando = "";
        tamanoArregloDeclarado = -1;
        elementosArreglo.clear();
        dentroDeInicializacionArreglo = false;
        tipoArregloActual = "";
        tipoSwitch = "";
        valoresCaseVistos.clear();
        lineaPrimerCase = -1;
        dentroDeSwitch = false;
        tipoSwitch = "";
        valoresCaseVistos.clear();
        lineaSwitch = -1;
        capturandoExpresionSwitch = false;
        tokensExpresionSwitch.clear();
    }

    // =============================================================================
    // PROCESAMIENTO SEMÁNTICO
    // =============================================================================

    private void procesarDerivacion(String noTerminal, String produccion) {
        if ((produccion.contains("favor") && !produccion.contains("porfavor")) || produccion.contains("metodillo")) {
            parametrosActuales.clear();
            tipoRetornoActual = "";
            funcionTieneRetorno = false;
            esperandoTipoRetorno = true;
        }

        if (produccion.contains("constantito")) {
            esConstanteActual = true;
        }

        if (produccion.contains("SiPersiste") || produccion.contains("siControla")) {
            nivelBucle++;
        }

        if (produccion.contains("enCasoSea")) {
            dentroDeSwitch = true;
        }

        if (produccion.contains("enCasoSea")) {
            dentroDeSwitch = true;
            tipoSwitch = "";
            valoresCaseVistos.clear();
            lineaPrimerCase = -1;
        }

        if (noTerminal.equals("CONSTRUCTOR") && produccion.contains("BLOQUE_CONSTRUCTOR")) {
            dentroDeConstructor = true;
        }
    }

    private void procesarMatchTerminal(String terminal, Token token) {
        if (token == null) return;

        // 1. TIPOS
        if (esTipoDato(terminal)) {
            if (esperandoTipoRetorno) {
                tipoRetornoActual = terminal;
                esperandoTipoRetorno = false;
            } else {
                tipoActual = terminal;
            }
        }

        // 2. ACLAMA
        if (terminal.equals("aclama")) {
            acabaDeVerAclama = true;
        }

        // 3. INVOCO
        if (terminal.equals("invoco")) {
            acabaDeVerInvoco = true;
        }

        // 4. PUNTO
        if (terminal.equals(".")) {
            if (acabaDeVerAclama) {
                vieneDeAclama = true;
                acabaDeVerAclama = false;
            }
        }

        // 5. COLECTAR TOKENS DE EXPRESIÓN
        if (dentroDeExpresion) {
            if (terminal.equals("entero") && token.type == TokenType.ENTERO) {
                expresionTokens.add(token.literal);
            } else if (terminal.equals("decimal") && token.type == TokenType.REAL) {
                expresionTokens.add(((Number) token.literal).floatValue());
            }
            // ========== AGREGAR ESTOS CASOS ==========
            else if (terminal.equals("TRUE") && token.type == TokenType.BOOLEAN) {
                boolean valorBool = token.lexeme.equals("true");
                expresionTokens.add("BOOL:" + valorBool);
            }
            else if (terminal.equals("cadena") && token.type == TokenType.STRING) {
                expresionTokens.add("STR:" + token.literal);
            }
            else if (terminal.equals("char") && token.type == TokenType.CHAR) {
                expresionTokens.add("CHAR:" + token.literal);
            }
            // ==========================================
            else if (terminal.equals("id")) {
                if (!token.lexeme.equals(nombreVarAsignando)) {
                    expresionTokens.add(token.lexeme);
                }
            } else if (terminal.equals("+") || terminal.equals("-") ||
                    terminal.equals("*") || terminal.equals("/")) {
                expresionTokens.add(terminal);
            } else if (terminal.equals("(")) {
                expresionTokens.add("(");
            } else if (terminal.equals(")")) {
                expresionTokens.add(")");
            }
            // Operadores de comparación
            else if (terminal.equals("<") || terminal.equals(">") ||
                    terminal.equals("<=") || terminal.equals(">=") ||
                    terminal.equals("==") || terminal.equals("!=")) {
                expresionTokens.add(terminal);
            }
            // Operadores lógicos
            else if (terminal.equals("&&") || terminal.equals("||")) {
                expresionTokens.add(terminal);
            }
        }

        // 6. IDENTIFICADORES
        if (terminal.equals("id")) {
            procesarIdentificador(token);
        }

        // 6b. MANEJO DE ARREGLOS - Capturar tamaño y elementos
        if (terminal.equals("[")) {
            // Mirar si el siguiente token es un entero (tamaño del arreglo)
            if (currentTokenIndex + 1 < tokens.size()) {
                Token siguienteToken = tokens.get(currentTokenIndex + 1);
                if (siguienteToken.type == TokenType.ENTERO) {
                    tamanoArregloDeclarado = (Integer) siguienteToken.literal;
                    tipoArregloActual = tipoActual; // Guardar el tipo del arreglo

                    // Validar tamaño >= 0
                    if (tamanoArregloDeclarado < 0) {
                        agregarError("Tamaño de arreglo no puede ser negativo: " + tamanoArregloDeclarado + " (línea " + token.line + ")");
                    }
                }
            }
        }

        // Detectar inicio de inicialización de arreglo (después de =)
        if (terminal.equals("=") && tamanoArregloDeclarado >= 0) {
            // El siguiente debe ser {
            if (currentTokenIndex + 1 < tokens.size()) {
                Token siguienteToken = tokens.get(currentTokenIndex + 1);
                if (siguienteToken.type == TokenType.LLAVE_IZQ) {
                    dentroDeInicializacionArreglo = true;
                    elementosArreglo.clear();
                }
            }
        }

        // Capturar elementos del arreglo
        if (dentroDeInicializacionArreglo) {
            if (terminal.equals("entero") && token.type == TokenType.ENTERO) {
                elementosArreglo.add("enterito");
            } else if (terminal.equals("decimal") && token.type == TokenType.REAL) {
                elementosArreglo.add("realito");
            } else if (terminal.equals("cadena") && token.type == TokenType.STRING) {
                elementosArreglo.add("cadenita");
            } else if (terminal.equals("char") && token.type == TokenType.CHAR) {
                elementosArreglo.add("charsito");
            } else if ((terminal.equals("TRUE") || terminal.equals("FALSE")) && token.type == TokenType.BOOLEAN) {
                elementosArreglo.add("booleanito");
            }
        }

        // 7. ENTRADA A BLOQUE
        if (terminal.equals("{")) {
            nivelBloque++;

            if (!funcionActual.isEmpty() || dentroDeConstructor) {
                dentroDeFuncion = true;
                nivelBloqueFuncion++;

                if (!claseActual.isEmpty() && !funcionActual.isEmpty()) {
                    dentroDeMetodo = true;
                }
            }

            if (funcionActual.isEmpty() && claseActual.isEmpty()) {
                for (int i = currentTokenIndex - 1; i >= Math.max(0, currentTokenIndex - 5); i--) {
                    if (tokens.get(i).type == TokenType.PRINCIPALSITO) {
                        funcionActual = "principalsito";
                        dentroDeFuncion = true;
                        nivelBloqueFuncion = 1;
                        tipoRetornoActual = "vacio";
                        break;
                    }
                }
            }
        }

        // 8. FIN DE DECLARACIÓN (:))
        // 8. FIN DE DECLARACIÓN (:))
        if (terminal.equals(":)")) {
            // Validar arreglo si estábamos en inicialización
            if (dentroDeInicializacionArreglo) {
                validarArregloConstantito(token.line);
                dentroDeInicializacionArreglo = false;
                tamanoArregloDeclarado = -1;
                elementosArreglo.clear();
                tipoArregloActual = "";
            }

            if (!dentroDeMetodo && !idPendiente.isEmpty() && !tipoActual.isEmpty()) {
                procesarFinDeclaracion(token);
            }

            if (dentroDeExpresion && varAsignando != null) {
                ejecutarAsignacionConExpresion(token.line);
                dentroDeExpresion = false;
            }
        }

        // 9. ASIGNACIÓN (porfavor)
        if (terminal.equals("porfavor")) {
            prepararAsignacion(token);
        }

        // 9b. VALIDACIÓN DE CONDICIONES BOOLEANAS EN ESTRUCTURAS DE CONTROL
        if (terminal.equals("siCumple") || terminal.equals("peroSiCumple") ||
                terminal.equals("SiPersiste") || terminal.equals("siControla")) {

            String contexto = terminal;

            // El siguiente token debe ser (
            if (currentTokenIndex + 1 < tokens.size() &&
                    tokens.get(currentTokenIndex + 1).type == TokenType.PAREN_IZQ) {

                // Capturar tokens de la condición
                List<Token> condicionTokens = new ArrayList<>();
                int parenCount = 0;
                int startIdx = currentTokenIndex + 1; // Empezar desde el (

                for (int i = startIdx; i < tokens.size(); i++) {
                    Token t = tokens.get(i);

                    if (t.type == TokenType.PAREN_IZQ) {
                        parenCount++;
                        if (parenCount > 1) { // No incluir el primer (
                            condicionTokens.add(t);
                        }
                    } else if (t.type == TokenType.PAREN_DER) {
                        parenCount--;
                        if (parenCount == 0) {
                            // Encontramos el cierre, validar ahora
                            break;
                        } else {
                            condicionTokens.add(t);
                        }
                    } else {
                        condicionTokens.add(t);
                    }
                }

                // Validar la condición
                if (!condicionTokens.isEmpty()) {
                    validarCondicionBooleana(condicionTokens, token.line, contexto);
                }
            }
        }

        // 10. CONTROL DE FLUJO
        if (terminal.equals("saltear")) {
            if (nivelBucle == 0) {
                agregarError("'saltear' fuera de bucle (línea " + token.line + ")");
            }
        }

        if (terminal.equals("parar")) {
            if (!dentroDeSwitch && nivelBucle == 0) {
                agregarError("'parar' fuera de switch o bucle (línea " + token.line + ")");
            }
        }

        if (terminal.equals("yo")) {
            if (claseActual.isEmpty()) {
                agregarError("'yo' usado fuera de clase (línea " + token.line + ")");
            }
        }

        // 11. RETORNA
        if (terminal.equals("retorna")) {
            if (funcionActual.isEmpty()) {
                agregarError("'retorna' fuera de función");
            } else {
                funcionTieneRetorno = true;
            }
        }

        // 11b cases
        if (terminal.equals("enCasoSea")) {
            dentroDeSwitch = true;
            tipoSwitch = "";
            valoresCaseVistos.clear();
            lineaSwitch = token.line;
            capturandoExpresionSwitch = false;
            tokensExpresionSwitch.clear();
        }

        // Detectar inicio de expresión (paréntesis después de enCasoSea)
        if (dentroDeSwitch && tipoSwitch.isEmpty() && terminal.equals("(")) {
            capturandoExpresionSwitch = true;
            tokensExpresionSwitch.clear();
        }

        // Capturar tokens de la expresión del switch
        if (capturandoExpresionSwitch && !terminal.equals("(")) {
            if (terminal.equals(")")) {
                // Terminamos de capturar, evaluar tipo
                capturandoExpresionSwitch = false;
                evaluarTipoExpresionSwitch(token.line);
            } else {
                // Agregar token a la expresión
                tokensExpresionSwitch.add(token);
            }
        }

        // ========== NUEVA VALIDACIÓN DE CASES ==========
        // Validar cuando encontramos DOS_PUNTOS después de un literal dentro del switch
        if (terminal.equals(":") && dentroDeSwitch && !tipoSwitch.isEmpty()) {
            // Mirar el token ANTERIOR (el valor del case)
            if (currentTokenIndex > 0) {
                Token tokenAnterior = tokens.get(currentTokenIndex - 1);

                // Verificar que NO es oSino (caso default)
                if (tokenAnterior.type != TokenType.OSINO) {
                    // Es un case con valor, validarlo
                    validarCaseConToken(tokenAnterior);
                }
            }
        }

        // 12. FIN DE BLOQUES (})
        if (terminal.equals("}")) {
            procesarCierreBloque();

            if (dentroDeSwitch && nivelBloque <= 0) {
                dentroDeSwitch = false;
                tipoSwitch = "";
                valoresCaseVistos.clear();
                lineaSwitch = -1;
                tokensExpresionSwitch.clear();
            }
        }
    }

    private void validarCaseConToken(Token tokenValor) {
        String tipoActualCase = "";
        Object valorActualCase = null;

        // Determinar tipo y valor del case según el token
        switch (tokenValor.type) {
            case ENTERO:
                tipoActualCase = "enterito";
                valorActualCase = tokenValor.literal;
                break;
            case REAL:
                tipoActualCase = "realito";
                valorActualCase = tokenValor.literal;
                break;
            case STRING:
                tipoActualCase = "cadenita";
                valorActualCase = tokenValor.literal;
                break;
            case CHAR:
                tipoActualCase = "charsito";
                valorActualCase = tokenValor.literal;
                break;
            case BOOLEAN:
                tipoActualCase = "booleanito";
                valorActualCase = tokenValor.lexeme.equals("true");
                break;
            case IDENTIFICADOR:
            case IDENTIFICADOR_MAYUSCULA:
                // Es una variable/constante, buscar su valor
                String nombreVar = tokenValor.lexeme;
                String scope = obtenerScopeActual();

                IdentificadorInfo varInfo = buscarIdentificador(scope + "." + nombreVar);
                if (varInfo == null) varInfo = buscarIdentificador("global." + nombreVar);

                if (varInfo == null) {
                    agregarError("Variable '" + nombreVar + "' no declarada en case (línea " + tokenValor.line + ")");
                    return;
                }

                tipoActualCase = varInfo.tipo;
                valorActualCase = varInfo.valor;
                break;
            default:
                // No es un literal válido para case
                return;
        }

        // Validar que el tipo coincida con la expresión del switch
        if (!tipoActualCase.equals(tipoSwitch)) {
            agregarError("Tipo incompatible en case: el switch evalúa tipo " +
                    tipoSwitch.toUpperCase() + " (línea " + lineaSwitch +
                    ") pero el case es de tipo " + tipoActualCase.toUpperCase() +
                    " (línea " + tokenValor.line + ")");
            return;
        }

        // Validar que el valor no se repita
        if (valoresCaseVistos.contains(valorActualCase)) {
            agregarError("Valor duplicado en case: " + valorActualCase +
                    " ya fue usado en este switch (línea " + tokenValor.line + ")");
            return;
        }

        // Si todo está bien, agregar el valor
        valoresCaseVistos.add(valorActualCase);
        imprimirAccionSemantica("Case válido: " + valorActualCase + " de tipo " +
                tipoActualCase.toUpperCase() + " (línea " + tokenValor.line + ")");
    }

    private void evaluarTipoExpresionSwitch(int linea) {
        if (tokensExpresionSwitch.isEmpty()) {
            agregarError("Switch sin expresión (línea " + linea + ")");
            return;
        }

        // Caso simple: una sola variable o literal
        if (tokensExpresionSwitch.size() == 1) {
            Token token = tokensExpresionSwitch.get(0);

            // Es un literal directo
            if (token.type == TokenType.ENTERO) {
                tipoSwitch = "enterito";
            } else if (token.type == TokenType.REAL) {
                tipoSwitch = "realito";
            } else if (token.type == TokenType.STRING) {
                tipoSwitch = "cadenita";
            } else if (token.type == TokenType.CHAR) {
                tipoSwitch = "charsito";
            } else if (token.type == TokenType.BOOLEAN) {
                tipoSwitch = "booleanito";
            }
            // Es un identificador (variable)
            else if (token.type == TokenType.IDENTIFICADOR ||
                    token.type == TokenType.IDENTIFICADOR_MAYUSCULA) {
                String nombreVar = token.lexeme;
                String scope = obtenerScopeActual();

                IdentificadorInfo varInfo = buscarIdentificador(scope + "." + nombreVar);
                if (varInfo == null) varInfo = buscarIdentificador("global." + nombreVar);

                if (varInfo == null) {
                    agregarError("Variable '" + nombreVar + "' no declarada en switch (línea " + linea + ")");
                    tipoSwitch = "enterito"; // Tipo por defecto para continuar
                } else {
                    tipoSwitch = varInfo.tipo;
                }
            } else {
                agregarError("Expresión inválida en switch (línea " + linea + ")");
                tipoSwitch = "enterito";
            }
        }
        // Caso complejo: expresión con operadores
        else {
            // Convertir tokens a formato para evaluarExpresion()
            List<Object> expresionTokens = new ArrayList<>();

            for (Token t : tokensExpresionSwitch) {
                if (t.type == TokenType.ENTERO) {
                    expresionTokens.add(t.literal);
                } else if (t.type == TokenType.REAL) {
                    expresionTokens.add(((Number) t.literal).floatValue());
                } else if (t.type == TokenType.BOOLEAN) {
                    expresionTokens.add("BOOL:" + t.lexeme.equals("true"));
                } else if (t.type == TokenType.STRING) {
                    expresionTokens.add("STR:" + t.lexeme);
                } else if (t.type == TokenType.CHAR) {
                    expresionTokens.add("CHAR:" + t.literal);
                } else if (t.type == TokenType.IDENTIFICADOR ||
                        t.type == TokenType.IDENTIFICADOR_MAYUSCULA) {
                    expresionTokens.add(t.lexeme);
                } else if (t.type == TokenType.SUMA) {
                    expresionTokens.add("+");
                } else if (t.type == TokenType.MENOS) {
                    expresionTokens.add("-");
                } else if (t.type == TokenType.ASTERISCO) {
                    expresionTokens.add("*");
                } else if (t.type == TokenType.DIVISION) {
                    expresionTokens.add("/");
                } else if (t.type == TokenType.PAREN_IZQ) {
                    expresionTokens.add("(");
                } else if (t.type == TokenType.PAREN_DER) {
                    expresionTokens.add(")");
                }
            }

            // Evaluar la expresión para obtener su tipo
            ExpresionResult resultado = evaluarExpresion(expresionTokens, linea);
            tipoSwitch = resultado.tipo;
        }

        imprimirAccionSemantica("Switch evaluado: expresión de tipo " + tipoSwitch.toUpperCase() +
                " (línea " + lineaSwitch + ")");
    }

    private void validarCase(String terminal, Token token) {
        String tipoActualCase = "";
        Object valorActualCase = null;

        // Determinar tipo y valor del case actual
        switch (terminal) {
            case "entero":
                tipoActualCase = "enterito";
                valorActualCase = token.literal;
                break;
            case "decimal":
                tipoActualCase = "realito";
                valorActualCase = token.literal;
                break;
            case "cadena":
                tipoActualCase = "cadenita";
                valorActualCase = token.literal;
                break;
            case "char":
                tipoActualCase = "charsito";
                valorActualCase = token.literal;
                break;
            case "TRUE":
                tipoActualCase = "booleanito";
                valorActualCase = token.lexeme.equals("true");
                break;
        }

        // Validar que el tipo coincida con la expresión del switch
        if (!tipoActualCase.equals(tipoSwitch)) {
            agregarError("Tipo incompatible en case: el switch evalúa tipo " +
                    tipoSwitch.toUpperCase() + " (línea " + lineaSwitch +
                    ") pero el case es de tipo " + tipoActualCase.toUpperCase() +
                    " (línea " + token.line + ")");
            return;
        }

        // Validar que el valor no se repita
        if (valoresCaseVistos.contains(valorActualCase)) {
            agregarError("Valor duplicado en case: " + valorActualCase +
                    " ya fue usado en este switch (línea " + token.line + ")");
            return;
        }

        // Si todo está bien, agregar el valor
        valoresCaseVistos.add(valorActualCase);
        imprimirAccionSemantica("Case válido: " + valorActualCase + " de tipo " +
                tipoActualCase.toUpperCase() + " (línea " + token.line + ")");
    }

    private void procesarIdentificador(Token token) {
        String nombre = token.lexeme;

        if (tipoActual.equals("clasesita")) {
            registrarClase(nombre, token.line);
            tipoActual = "";
            return;
        }

        if (!claseActual.isEmpty() && nombre.equals(claseActual) && !dentroDeConstructor) {
            dentroDeConstructor = true;
            return;
        }

        if (esClase(nombre) && tipoActual.isEmpty() && idPendiente.isEmpty()) {
            tipoActual = nombre;
            return;
        }

        if (vieneDeAclama) {
            if (!existeFuncion(nombre)) {
                agregarError("Función '" + nombre + "' no declarada (línea " + token.line + ")");
            }
            vieneDeAclama = false;
            return;
        }

        if (acabaDeVerInvoco && objetoInvocando.isEmpty()) {
            verificarIdentificadorDeclarado(nombre, token.line);
            objetoInvocando = nombre;
            return;
        }

        if (!objetoInvocando.isEmpty()) {
            String scope = obtenerScopeActual();
            IdentificadorInfo objInfo = buscarIdentificador(scope + "." + objetoInvocando);
            if (objInfo == null) objInfo = buscarIdentificador("global." + objetoInvocando);

            if (objInfo != null && objInfo.modificador.equals("objeto")) {
                String tipoClase = objInfo.tipo;
                String metodoKey = "clase_" + tipoClase + "." + nombre;
                IdentificadorInfo metodoInfo = buscarIdentificador(metodoKey);

                if (metodoInfo == null || !metodoInfo.modificador.equals("metodillo")) {
                    agregarError("Método '" + nombre + "' no existe en clase '" + tipoClase + "' (línea " + token.line + ")");
                }
            }

            acabaDeVerInvoco = false;
            objetoInvocando = "";
            return;
        }

        if (!tipoRetornoActual.isEmpty() && funcionActual.isEmpty() && claseActual.isEmpty()) {
            registrarFuncion(nombre, token.line);
            return;
        }

        if (!tipoRetornoActual.isEmpty() && !claseActual.isEmpty() && funcionActual.isEmpty()) {
            funcionActual = nombre;
            tipoActual = "";
            idPendiente = "";
            return;
        }

        if ((!funcionActual.isEmpty() || dentroDeConstructor) && !tipoActual.isEmpty() && !dentroDeFuncion) {
            registrarParametro(nombre, token.line);
            return;
        }

        if (!tipoActual.isEmpty() && idPendiente.isEmpty()) {
            idPendiente = nombre;
            return;
        }

        if (tipoActual.isEmpty() && idPendiente.isEmpty()) {
            verificarIdentificadorDeclarado(nombre, token.line);
            return;
        }
    }

    private void registrarClase(String nombre, int linea) {
        String key = "global." + nombre;

        if (tablaSimbolos.containsKey(key)) {
            agregarError("Clase '" + nombre + "' ya declarada en línea " + tablaSimbolos.get(key).linea);
            return;
        }

        IdentificadorInfo info = new IdentificadorInfo(nombre, nombre, null, "clasesita", linea, "global");
        tablaSimbolos.put(key, info);
        claseActual = nombre;

        imprimirAccionSemantica("Declarando clase: " + nombre);
    }

    private void registrarFuncion(String nombre, int linea) {
        String key = "global." + nombre;

        if (tablaSimbolos.containsKey(key)) {
            agregarError("Función '" + nombre + "' ya declarada en línea " + tablaSimbolos.get(key).linea);
            return;
        }

        IdentificadorInfo info = new IdentificadorInfo(nombre, tipoRetornoActual, null, "favor", linea, "global");
        tablaSimbolos.put(key, info);

        funcionActual = nombre;
        idPendiente = "";
        tipoActual = "";

        imprimirAccionSemantica("Declarando función: " + nombre + " (Retorna: " + tipoRetornoActual.toUpperCase() + ")");
    }

    private void registrarMetodo(Token token) {
        if (funcionActual.isEmpty()) return;

        String scope = "clase_" + claseActual;
        String key = scope + "." + funcionActual;

        if (tablaSimbolos.containsKey(key)) {
            agregarError("Método '" + funcionActual + "' ya declarado en clase " + claseActual);
            return;
        }

        IdentificadorInfo info = new IdentificadorInfo(funcionActual, tipoRetornoActual, null, "metodillo", token.line, scope);

        for (ParametroInfo param : parametrosActuales) {
            info.tiposParametros.add(param.tipo);
            info.nombresParametros.add(param.nombre);
        }

        tablaSimbolos.put(key, info);

        imprimirAccionSemantica("Declarando método: " + funcionActual + " (Tipo: " + tipoRetornoActual.toUpperCase() +
                ", Scope: " + scope + ")");
    }

    private void registrarParametro(String nombre, int linea) {
        parametrosActuales.add(new ParametroInfo(tipoActual, nombre));

        String scope = !claseActual.isEmpty() ? "clase_" + claseActual :
                funcionActual.equals("principalsito") ? "principalsito" : "funcion_" + funcionActual;
        String key = scope + "." + nombre;

        IdentificadorInfo info = new IdentificadorInfo(nombre, tipoActual, obtenerValorDefault(tipoActual),
                "parámetro", linea, scope);
        tablaSimbolos.put(key, info);

        imprimirAccionSemantica("Parámetro: " + nombre + " (Tipo: " + tipoActual.toUpperCase() + ")");

        tipoActual = "";
    }

    private void procesarFinDeclaracion(Token token) {
        if (!idPendiente.isEmpty() && !tipoActual.isEmpty()) {
            String scope = obtenerScopeActual();
            String key = scope + "." + idPendiente;

            if (tablaSimbolos.containsKey(key)) {
                agregarError("Identificador '" + idPendiente + "' ya declarado en " + scope);
                return;
            }

            String modificador;
            if (esClase(tipoActual)) {
                modificador = "objeto";
            } else if (esConstanteActual) {
                modificador = "constantito";
            } else {
                modificador = "variable";
            }

            IdentificadorInfo info = new IdentificadorInfo(idPendiente, tipoActual, obtenerValorDefault(tipoActual),
                    modificador, token.line, scope);
            tablaSimbolos.put(key, info);

            imprimirAccionSemantica("Declarando: " + idPendiente + " (Tipo: " + tipoActual.toUpperCase() +
                    ", Modificador: " + modificador + ", Scope: " + scope + ")");

            idPendiente = "";
            tipoActual = "";
            esConstanteActual = false;
        }
    }

    private void prepararAsignacion(Token token) {
        int idx = currentTokenIndex + 1;

        if (idx < tokens.size() &&
                (tokens.get(idx).type == TokenType.IDENTIFICADOR ||
                        tokens.get(idx).type == TokenType.IDENTIFICADOR_MAYUSCULA)) {

            String varNombre = tokens.get(idx).lexeme;
            String scope = obtenerScopeActual();

            IdentificadorInfo var = buscarIdentificador(scope + "." + varNombre);
            if (var == null) var = buscarIdentificador("global." + varNombre);

            if (var == null) {
                agregarError("Variable '" + varNombre + "' no declarada (línea " + token.line + ")");
                return;
            }

            if (var.modificador.equals("constantito") && var.inicializada) {
                agregarError("No se puede reasignar constante '" + varNombre + "' (línea " + token.line + ")");
                return;
            }

            varAsignando = var;
            nombreVarAsignando = varNombre;
            expresionTokens.clear();
            tipoExpresionActual = "";
            dentroDeExpresion = true;
        }
    }

    private void ejecutarAsignacionConExpresion(int linea) {
        if (varAsignando == null || expresionTokens.isEmpty()) return;

        // Evaluar expresión con precedencia correcta
        ExpresionResult resultado = evaluarExpresion(expresionTokens, linea);

        // VALIDACIÓN DE COMPATIBILIDAD DE TIPOS
        String tipoVariable = varAsignando.tipo;
        String tipoExpresion = resultado.tipo;

        boolean hayError = false;

        // ========================================
        // PRIORIDAD 1: Validar expresiones booleanas
        // ========================================

        // Si la expresión es booleana pero la variable NO es booleanito
        if (resultado.esBooleano && !tipoVariable.equals("booleanito")) {
            agregarError("No se puede asignar BOOLEANITO a variable " +
                    tipoVariable.toUpperCase() + " '" + nombreVarAsignando + "' (línea " + linea + ")");
            hayError = true;
        }

        // Si la variable es booleanito pero la expresión NO es booleana
        if (tipoVariable.equals("booleanito") && !resultado.esBooleano) {
            agregarError("No se puede asignar " + tipoExpresion.toUpperCase() +
                    " a variable BOOLEANITO '" + nombreVarAsignando + "' (línea " + linea + ")");
            hayError = true;
        }

        // ========================================
        // PRIORIDAD 2: Validar otros tipos (solo si no es booleano)
        // ========================================

        if (!resultado.esBooleano && !hayError) {
            if (tipoVariable.equals("enterito")) {
                if (tipoExpresion.equals("realito")) {
                    agregarError("No se puede asignar REALITO a variable ENTERITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                    hayError = true;
                } else if (tipoExpresion.equals("cadenita")) {
                    agregarError("No se puede asignar CADENITA a variable ENTERITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                    hayError = true;
                } else if (tipoExpresion.equals("charsito")) {
                    agregarError("No se puede asignar CHARSITO a variable ENTERITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                    hayError = true;
                }
            } else if (tipoVariable.equals("realito")) {
                if (tipoExpresion.equals("cadenita")) {
                    agregarError("No se puede asignar CADENITA a variable REALITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                    hayError = true;
                } else if (tipoExpresion.equals("charsito")) {
                    agregarError("No se puede asignar CHARSITO a variable REALITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                    hayError = true;
                } else if (tipoExpresion.equals("enterito")) {
                    agregarWarning("Conversión implícita: ENTERITO → REALITO en '" + nombreVarAsignando + "'");
                }
            } else if (tipoVariable.equals("cadenita")) {
                if (!tipoExpresion.equals("cadenita")) {
                    agregarError("No se puede asignar " + tipoExpresion.toUpperCase() +
                            " a variable CADENITA '" + nombreVarAsignando + "' (línea " + linea + ")");
                    hayError = true;
                }
            } else if (tipoVariable.equals("charsito")) {
                if (!tipoExpresion.equals("charsito")) {
                    agregarError("No se puede asignar " + tipoExpresion.toUpperCase() +
                            " a variable CHARSITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                    hayError = true;
                }
            }
        }

        if (hayError) {
            limpiarAsignacion();
            return;
        }

        // ========================================
        // Asignar valor según el tipo
        // ========================================

        if (resultado.esBooleano) {
            // Es una expresión booleana
            varAsignando.valor = resultado.valorBooleano;
            imprimirAccionSemantica("Asignando: " + nombreVarAsignando + " = " + resultado.valorBooleano);
        } else if (tipoVariable.equals("enterito")) {
            varAsignando.valor = (int) resultado.valor;
            imprimirAccionSemantica("Asignando: " + nombreVarAsignando + " = " + (int) resultado.valor);
        } else if (tipoVariable.equals("realito")) {
            varAsignando.valor = resultado.valor;
            imprimirAccionSemantica("Asignando: " + nombreVarAsignando + " = " + resultado.valor);
        } else if (tipoVariable.equals("cadenita")) {
            // ========== EXTRAER VALOR REAL DEL STRING ==========
            String valorString = extraerValorString(expresionTokens);
            varAsignando.valor = valorString;
            imprimirAccionSemantica("Asignando: " + nombreVarAsignando + " = \"" + valorString + "\"");
        } else if (tipoVariable.equals("charsito")) {
            // ========== EXTRAER VALOR REAL DEL CHAR ==========
            char valorChar = extraerValorChar(expresionTokens);
            varAsignando.valor = valorChar;
            imprimirAccionSemantica("Asignando: " + nombreVarAsignando + " = '" + valorChar + "'");
        }

        varAsignando.inicializada = true;
        limpiarAsignacion();
    }

    private String extraerValorString(List<Object> tokens) {
        for (Object token : tokens) {
            if (token instanceof String) {
                String str = (String) token;
                if (str.startsWith("STR:")) {
                    return str.substring(4); // Quitar el prefijo "STR:"
                }
            }
        }
        return ""; // Valor por defecto si no se encuentra
    }

    private char extraerValorChar(List<Object> tokens) {
        for (Object token : tokens) {
            if (token instanceof String) {
                String str = (String) token;
                if (str.startsWith("CHAR:")) {
                    String charStr = str.substring(5); // Quitar el prefijo "CHAR:"
                    if (!charStr.isEmpty()) {
                        return charStr.charAt(0);
                    }
                }
            }
        }
        return '\0'; // Valor por defecto si no se encuentra
    }

    private void limpiarAsignacion() {
        varAsignando = null;
        nombreVarAsignando = "";
        dentroDeExpresion = false;
        expresionTokens.clear();
        tipoExpresionActual = "";
    }

    private void procesarCierreBloque() {
        nivelBloque--;

        if ((!funcionActual.isEmpty() || dentroDeConstructor) && nivelBloqueFuncion > 0) {
            nivelBloqueFuncion--;
        }

        if (nivelBloqueFuncion == 0) {
            if (dentroDeConstructor) {
                dentroDeConstructor = false;
                funcionActual = "";
                tipoRetornoActual = "";
                parametrosActuales.clear();
                dentroDeFuncion = false;
            } else if (!funcionActual.isEmpty()) {
                if (!tipoRetornoActual.equals("vacio") && !funcionTieneRetorno) {
                    agregarError("Función '" + funcionActual + "' debe tener 'retorna'");
                }

                if (!claseActual.isEmpty()) {
                    registrarMetodo(tokens.get(currentTokenIndex - 1));
                } else {
                    if (!funcionActual.equals("principalsito")) {
                        String key = "global." + funcionActual;
                        IdentificadorInfo funcInfo = tablaSimbolos.get(key);
                        if (funcInfo != null) {
                            funcInfo.tiposParametros.clear();
                            funcInfo.nombresParametros.clear();
                            for (ParametroInfo param : parametrosActuales) {
                                funcInfo.tiposParametros.add(param.tipo);
                                funcInfo.nombresParametros.add(param.nombre);
                            }
                        }
                    }
                }

                funcionActual = "";
                tipoRetornoActual = "";
                funcionTieneRetorno = false;
                parametrosActuales.clear();
                dentroDeFuncion = false;
                dentroDeMetodo = false;
                idPendiente = "";
                tipoActual = "";
            }
        }

        if (nivelBloque == 0 && !claseActual.isEmpty()) {
            claseActual = "";
        }

        if (nivelBucle > 0) {
            nivelBucle--;
        }

        if (dentroDeSwitch) {
            dentroDeSwitch = false;
        }
    }

    // =============================================================================
    // UTILIDADES
    // =============================================================================
    private void validarArregloConstantito(int linea) {
        // 1. Verificar que no se exceda el tamaño declarado
        if (elementosArreglo.size() > tamanoArregloDeclarado) {
            agregarError("Arreglo '" + idPendiente + "' declarado con tamaño " + tamanoArregloDeclarado +
                    " pero se inicializaron " + elementosArreglo.size() + " elementos (línea " + linea + ")");
            return;
        }

        // 2. Verificar compatibilidad de tipos de cada elemento
        for (int i = 0; i < elementosArreglo.size(); i++) {
            String tipoElemento = elementosArreglo.get(i);

            // Validar compatibilidad según el tipo del arreglo
            if (tipoArregloActual.equals("enterito")) {
                if (!tipoElemento.equals("enterito")) {
                    agregarError("Tipo incompatible en arreglo '" + idPendiente + "': se esperaba ENTERITO pero se encontró " +
                            tipoElemento.toUpperCase() + " en posición " + i + " (línea " + linea + ")");
                }
            } else if (tipoArregloActual.equals("realito")) {
                // realito puede aceptar enterito (conversión implícita) o realito
                if (!tipoElemento.equals("realito") && !tipoElemento.equals("enterito")) {
                    agregarError("Tipo incompatible en arreglo '" + idPendiente + "': se esperaba REALITO pero se encontró " +
                            tipoElemento.toUpperCase() + " en posición " + i + " (línea " + linea + ")");
                }
            } else if (tipoArregloActual.equals("cadenita")) {
                if (!tipoElemento.equals("cadenita")) {
                    agregarError("Tipo incompatible en arreglo '" + idPendiente + "': se esperaba CADENITA pero se encontró " +
                            tipoElemento.toUpperCase() + " en posición " + i + " (línea " + linea + ")");
                }
            } else if (tipoArregloActual.equals("charsito")) {
                if (!tipoElemento.equals("charsito")) {
                    agregarError("Tipo incompatible en arreglo '" + idPendiente + "': se esperaba CHARSITO pero se encontró " +
                            tipoElemento.toUpperCase() + " en posición " + i + " (línea " + linea + ")");
                }
            } else if (tipoArregloActual.equals("booleanito")) {
                if (!tipoElemento.equals("booleanito")) {
                    agregarError("Tipo incompatible en arreglo '" + idPendiente + "': se esperaba BOOLEANITO pero se encontró " +
                            tipoElemento.toUpperCase() + " en posición " + i + " (línea " + linea + ")");
                }
            }
        }

        imprimirAccionSemantica("Arreglo '" + idPendiente + "' validado: " + elementosArreglo.size() + "/" + tamanoArregloDeclarado +
                " elementos de tipo " + tipoArregloActual.toUpperCase());
    }

    private boolean esClase(String nombre) {
        return tablaSimbolos.containsKey("global." + nombre) &&
                tablaSimbolos.get("global." + nombre).modificador.equals("clasesita");
    }

    private boolean existeFuncion(String nombre) {
        IdentificadorInfo info = tablaSimbolos.get("global." + nombre);
        return info != null && (info.modificador.equals("favor") || info.modificador.equals("metodillo"));
    }

    private IdentificadorInfo buscarIdentificador(String key) {
        return tablaSimbolos.get(key);
    }

    private void verificarIdentificadorDeclarado(String nombre, int linea) {
        String scope = obtenerScopeActual();
        IdentificadorInfo var = buscarIdentificador(scope + "." + nombre);
        if (var == null) var = buscarIdentificador("global." + nombre);
        if (var == null) {
            agregarError("Variable '" + nombre + "' no declarada (línea " + linea + ")");
        }
    }

    private String obtenerScopeActual() {
        if (!claseActual.isEmpty()) return "clase_" + claseActual;
        if (!funcionActual.isEmpty()) {
            if (funcionActual.equals("principalsito")) return "principalsito";
            return "funcion_" + funcionActual;
        }
        return "global";
    }

    private Object obtenerValorDefault(String tipo) {
        switch (tipo) {
            case "enterito": return 0;
            case "realito": return 0.0f;
            case "booleanito": return false;
            case "charsito": return '\0';
            case "cadenita": return "";
            case "vacio": return null;
            default: return null;
        }
    }

    private boolean esTipoDato(String terminal) {
        return terminal.equals("enterito") || terminal.equals("realito") ||
                terminal.equals("booleanito") || terminal.equals("charsito") ||
                terminal.equals("cadenita") || terminal.equals("vacio") ||
                terminal.equals("clasesita");
    }

    private String obtenerTerminalActual() {
        if (currentTokenIndex >= tokens.size()) return "$";

        Token token = tokens.get(currentTokenIndex);

        if (token.type == TokenType.BOOLEAN) {
            return token.lexeme.equals("true") ? "TRUE" : "FALSE";
        }

        return tokenToTerminal.getOrDefault(token.type, token.lexeme);
    }

    private String obtenerEntradaRestante() {
        StringBuilder sb = new StringBuilder();
        for (int i = currentTokenIndex; i < tokens.size(); i++) {
            sb.append(tokens.get(i).lexeme).append(" ");
            if (sb.length() > 70) {
                return sb.substring(0, 67) + "...";
            }
        }
        return sb.toString().trim();
    }

    private void avanzarToken() {
        if (currentTokenIndex < tokens.size()) {
            currentTokenIndex++;
        }
    }

    private String buscarProduccion(String noTerminal, String terminal) {
        Map<String, String> producciones = TAS.get(noTerminal);
        return producciones != null ? producciones.get(terminal) : null;
    }

    private void empilar(String produccion) {
        if (isEpsilon(produccion)) return;

        String[] simbolos = produccion.split("\\s+");
        for (String simbolo : simbolos) {
            if (!simbolo.isEmpty() && !isEpsilon(simbolo)) {
                pila.push(simbolo);
            }
        }
    }

    private String mostrarPila() {
        List<String> elementos = new ArrayList<>(pila);
        Collections.reverse(elementos);

        String pilaStr = String.join(" ", elementos);

        if (pilaStr.length() > 35) {
            pilaStr = pilaStr.substring(0, 32) + "...";
        }

        return pilaStr;
    }

    private void agregarError(String mensaje) {
        String clave = mensaje.toLowerCase().replaceAll("\\s+", " ");

        if (!erroresReportados.contains(clave)) {
            erroresReportados.add(clave);
            erroresSemanticos.add("❌ ERROR: " + mensaje);
            System.out.println("\n   >>> [Accion Semantica] [ERROR] " + mensaje);
        }
    }

    private void agregarWarning(String mensaje) {
        warnings.add("⚠️  WARNING: " + mensaje);
        System.out.println("\n   >>> [Accion Semantica] [WARNING] " + mensaje);
    }

    private void imprimirAccionSemantica(String mensaje) {
        System.out.println("\n   >>> [Accion Semantica] " + mensaje);
    }

    // =============================================================================
    // REPORTE FINAL
    // =============================================================================

    private void imprimirReporteSemantico() {
        System.out.println("\n" + "=".repeat(140));
        System.out.println(" ".repeat(45) + "TABLA ÚNICA DE IDENTIFICADORES");
        System.out.println("=".repeat(140));

        System.out.println("\n📋 IDENTIFICADORES DECLARADOS:");
        System.out.println("-".repeat(140));
        System.out.printf("%-20s %-15s %-20s %-15s %-30s%n",
                "NOMBRE", "TIPO", "VALOR", "MODIFICADOR", "SCOPE");
        System.out.println("-".repeat(140));

        if (tablaSimbolos.isEmpty()) {
            System.out.println(" (No se declararon identificadores)");
        } else {
            List<Map.Entry<String, IdentificadorInfo>> entradas = new ArrayList<>(tablaSimbolos.entrySet());
            entradas.sort((a, b) -> {
                int scopeCompare = a.getValue().scope.compareTo(b.getValue().scope);
                if (scopeCompare != 0) return scopeCompare;
                return a.getValue().nombre.compareTo(b.getValue().nombre);
            });

            for (Map.Entry<String, IdentificadorInfo> entry : entradas) {
                IdentificadorInfo info = entry.getValue();

                System.out.printf("%-20s %-15s %-20s %-15s %-30s%n",
                        info.nombre,
                        info.tipo,
                        info.valor != null ? info.valor.toString() : "null",
                        info.modificador,
                        info.scope);
            }
        }

        if (!erroresSemanticos.isEmpty()) {
            System.out.println("\n❌ ERRORES SEMÁNTICOS ENCONTRADOS:");
            System.out.println("-".repeat(140));
            for (String error : erroresSemanticos) {
                System.out.println("   " + error);
            }
        }

        if (!warnings.isEmpty()) {
            System.out.println("\n⚠️  ADVERTENCIAS:");
            System.out.println("-".repeat(140));
            for (String warning : warnings) {
                System.out.println("   " + warning);
            }
        }

        System.out.println("\n- RESUMEN:");
        System.out.println("-".repeat(140));

        long totalClases = tablaSimbolos.values().stream().filter(i -> i.modificador.equals("clasesita")).count();
        long totalFunciones = tablaSimbolos.values().stream().filter(i -> i.modificador.equals("favor")).count();
        long totalMetodos = tablaSimbolos.values().stream().filter(i -> i.modificador.equals("metodillo")).count();
        long totalVariables = tablaSimbolos.values().stream().filter(i -> i.modificador.equals("variable")).count();
        long totalConstantes = tablaSimbolos.values().stream().filter(i -> i.modificador.equals("constantito")).count();
        long totalParametros = tablaSimbolos.values().stream().filter(i -> i.modificador.equals("parámetro")).count();
        long totalObjetos = tablaSimbolos.values().stream().filter(i -> i.modificador.equals("objeto")).count();

        System.out.println("   Clases (clasesita):    " + totalClases);
        System.out.println("   Funciones (favor):     " + totalFunciones);
        System.out.println("   Métodos (metodillo):   " + totalMetodos);
        System.out.println("   Variables:             " + totalVariables);
        System.out.println("   Constantes:            " + totalConstantes);
        System.out.println("   Parámetros:            " + totalParametros);
        System.out.println("   Objetos:               " + totalObjetos);
        System.out.println("   ─────────────────────────────");
        System.out.println("   Total identificadores: " + tablaSimbolos.size());
        System.out.println("   Errores semánticos:    " + erroresSemanticos.size());
        System.out.println("   Warnings:              " + warnings.size());
        System.out.println("=".repeat(140));

        if (erroresSemanticos.isEmpty()) {
            System.out.println("\n✅ ANÁLISIS SEMÁNTICO COMPLETADO SIN ERRORES");
        } else {
            System.out.println("\n❌ ANÁLISIS SEMÁNTICO COMPLETADO CON ERRORES");
        }
    }
}