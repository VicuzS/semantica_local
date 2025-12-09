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
        String tipo;           // enterito, realito, vacio, nombre de clase, etc.
        Object valor;
        String modificador;    // variable, constantito, parámetro, metodillo, favor, clasesita, objeto
        boolean inicializada;
        int linea;
        String scope;

        // Para funciones y métodos
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
    private Set<String> erroresReportados = new HashSet<>();  // Para evitar duplicados

    // =============================================================================
    // CONTROL DE CONTEXTO
    // =============================================================================

    private String tipoActual = "";
    private String idPendiente = "";
    private boolean esConstanteActual = false;
    private String funcionActual = "";
    private String claseActual = "";
    private int nivelBloque = 0; // Rastrear profundidad de bloques globales
    private int nivelBloqueFuncion = 0; // Rastrear profundidad dentro de función/método
    private int nivelBucle = 0;
    private boolean dentroDeSwitch = false;
    private boolean esperandoTipoRetorno = false;
    private boolean dentroDeFuncion = false;
    private boolean acabaDeVerAclama = false;
    private boolean vieneDeAclama = false;
    private boolean acabaDeVerInvoco = false;
    private String objetoInvocando = ""; // Para rastrear el objeto en "invoco objeto.metodo()"
    private boolean dentroDeConstructor = false;
    private boolean dentroDeMetodo = false; // Para rastrear si estamos en el cuerpo de un método

    private List<ParametroInfo> parametrosActuales = new ArrayList<>();
    private String tipoRetornoActual = "";
    private boolean funcionTieneRetorno = false;

    private String nombreVarAsignando = "";
    private IdentificadorInfo varAsignando = null;

    // Acumulador semántico
    private float acumulador = 0;
    private boolean expresionTieneReales = false;
    private String tipoExpresionActual = "";  // Tipo detectado de la expresión actual
    private String operadorActual = "+";
    private boolean dentroDeExpresion = false;

    public Parser() {
        inicializarMapeoTokens();
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
    // CARGA DE TABLA TAS (sin cambios)
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

        // Limpiar estado
        tablaSimbolos.clear();
        erroresSemanticos.clear();
        warnings.clear();
        erroresReportados.clear();  // Limpiar errores reportados
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
        acumulador = 0;
        expresionTieneReales = false;
        operadorActual = "+";
        dentroDeExpresion = false;
        varAsignando = null;
        nombreVarAsignando = "";
    }

    // =============================================================================
    // PROCESAMIENTO SEMÁNTICO
    // =============================================================================

    private void procesarDerivacion(String noTerminal, String produccion) {
        // Detectar función (favor) o método (metodillo)
        if ((produccion.contains("favor") && !produccion.contains("porfavor")) || produccion.contains("metodillo")) {
            parametrosActuales.clear();
            tipoRetornoActual = "";
            funcionTieneRetorno = false;
            esperandoTipoRetorno = true;  // Activar INMEDIATAMENTE
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

        // 2. CLASESITA
        if (terminal.equals("clasesita")) {
            // Siguiente ID será nombre de clase
        }

        // 3. ACLAMA
        if (terminal.equals("aclama")) {
            acabaDeVerAclama = true;
        }

        // 3b. INVOCO
        if (terminal.equals("invoco")) {
            acabaDeVerInvoco = true;
        }

        // 4. PUNTO después de aclama o invoco
        if (terminal.equals(".")) {
            if (acabaDeVerAclama) {
                vieneDeAclama = true;
                acabaDeVerAclama = false;
            }
            // Para invoco, el punto confirma que es llamada a método de objeto
            // acabaDeVerInvoco se maneja en procesarIdentificador
        }

        // 5. LITERALES Y OPERADORES (Acumulador)
        if (dentroDeExpresion) {
            if (terminal.equals("entero") && token.type == TokenType.ENTERO) {
                tipoExpresionActual = "enterito";
                int valor = (Integer) token.literal;
                acumulador = aplicarOperacion(acumulador, valor, operadorActual);
                operadorActual = "+";
            } else if (terminal.equals("decimal") && token.type == TokenType.REAL) {
                tipoExpresionActual = "realito";
                expresionTieneReales = true;
                float valor = ((Number) token.literal).floatValue();
                acumulador = aplicarOperacion(acumulador, valor, operadorActual);
                operadorActual = "+";
            } else if (terminal.equals("cadena") && token.type == TokenType.STRING) {
                tipoExpresionActual = "cadenita";
                // No acumular, solo marcar el tipo
            } else if (terminal.equals("char") && token.type == TokenType.CHAR) {
                tipoExpresionActual = "charsito";
                // No acumular, solo marcar el tipo
            } else if ((terminal.equals("TRUE") || terminal.equals("FALSE")) && token.type == TokenType.BOOLEAN) {
                tipoExpresionActual = "booleanito";
                // No acumular, solo marcar el tipo
            } else if (terminal.equals("+")) {
                operadorActual = "+";
            } else if (terminal.equals("-")) {
                operadorActual = "-";
            } else if (terminal.equals("*")) {
                operadorActual = "*";
            } else if (terminal.equals("/")) {
                operadorActual = "/";
            }
        }

        // 6. IDENTIFICADORES
        if (terminal.equals("id")) {
            procesarIdentificador(token);

            // Si estamos en expresión y es uso de variable, acumular
            if (dentroDeExpresion && !vieneDeAclama && tipoActual.isEmpty()) {
                String scope = obtenerScopeActual();
                IdentificadorInfo var = buscarIdentificador(scope + "." + token.lexeme);
                if (var == null) var = buscarIdentificador("global." + token.lexeme);
                if (var != null && var.valor != null) {
                    // Detectar tipo de la variable
                    if (tipoExpresionActual.isEmpty()) {
                        tipoExpresionActual = var.tipo;
                    }

                    // Solo intentar acumular si es numérico
                    if (var.valor instanceof Number) {
                        if (var.tipo.equals("realito")) expresionTieneReales = true;
                        float valor = ((Number) var.valor).floatValue();
                        acumulador = aplicarOperacion(acumulador, valor, operadorActual);
                        operadorActual = "+";
                    }
                }
            }
        }

        // 7. ENTRADA A BLOQUE
        if (terminal.equals("{")) {
            nivelBloque++; // Incrementar nivel de bloque global

            // Si ya hay una función activa O estamos en constructor, marcar nivel
            if (!funcionActual.isEmpty() || dentroDeConstructor) {
                dentroDeFuncion = true;
                nivelBloqueFuncion++; // Incrementar nivel dentro de función/constructor

                // Si estamos en una clase y hay función activa, es un método
                if (!claseActual.isEmpty() && !funcionActual.isEmpty()) {
                    dentroDeMetodo = true;
                }
            }

            // Detectar entrada a principalsito
            if (funcionActual.isEmpty() && claseActual.isEmpty()) {
                for (int i = currentTokenIndex - 1; i >= Math.max(0, currentTokenIndex - 5); i--) {
                    if (tokens.get(i).type == TokenType.PRINCIPALSITO) {
                        funcionActual = "principalsito";
                        dentroDeFuncion = true;
                        nivelBloqueFuncion = 1; // Iniciar nivel
                        tipoRetornoActual = "vacio";
                        break;
                    }
                }
            }
        }

        // 8. FIN DE DECLARACIÓN (:))
        if (terminal.equals(":)")) {
            // Si estamos dentro de un método, NO registrar nada
            if (!dentroDeMetodo && !idPendiente.isEmpty() && !tipoActual.isEmpty()) {
                procesarFinDeclaracion(token);
            }

            // Si estábamos en expresión, ejecutar asignación
            if (dentroDeExpresion && varAsignando != null) {
                ejecutarAsignacionConAcumulador(token.line);
                dentroDeExpresion = false;
            }
        }

        // 9. ASIGNACIÓN (porfavor)
        if (terminal.equals("porfavor")) {
            prepararAsignacion(token);
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

        // 12. FIN DE BLOQUES (})
        if (terminal.equals("}")) {
            procesarCierreBloque();
        }
    }

    private void procesarIdentificador(Token token) {
        String nombre = token.lexeme;

        // CONTEXTO 1: Nombre de clase (después de clasesita)
        if (tipoActual.equals("clasesita")) {
            registrarClase(nombre, token.line);
            tipoActual = "";
            return;
        }

        // CONTEXTO 2: Nombre de constructor
        if (!claseActual.isEmpty() && nombre.equals(claseActual) && !dentroDeConstructor) {
            dentroDeConstructor = true;
            return;
        }

        // CONTEXTO 3: Tipo de clase para instanciar
        if (esClase(nombre) && tipoActual.isEmpty() && idPendiente.isEmpty()) {
            tipoActual = nombre;
            return;
        }

        // CONTEXTO 4: Llamada a función con aclama
        if (vieneDeAclama) {
            if (!existeFuncion(nombre)) {
                agregarError("Función '" + nombre + "' no declarada (línea " + token.line + ")");
            }
            vieneDeAclama = false;
            return;
        }

        // CONTEXTO 4b: Llamada a método con invoco objeto.metodo()
        if (acabaDeVerInvoco && objetoInvocando.isEmpty()) {
            // Este es el nombre del objeto (ej: persona1)
            verificarIdentificadorDeclarado(nombre, token.line);
            objetoInvocando = nombre;
            return;
        }

        // CONTEXTO 4c: Nombre del método después de invoco objeto.
        if (!objetoInvocando.isEmpty()) {
            // Verificar que el objeto es de tipo clase
            String scope = obtenerScopeActual();
            IdentificadorInfo objInfo = buscarIdentificador(scope + "." + objetoInvocando);
            if (objInfo == null) objInfo = buscarIdentificador("global." + objetoInvocando);

            if (objInfo != null && objInfo.modificador.equals("objeto")) {
                // Obtener la clase del objeto
                String tipoClase = objInfo.tipo;

                // Verificar que el método existe en esa clase
                String metodoKey = "clase_" + tipoClase + "." + nombre;
                IdentificadorInfo metodoInfo = buscarIdentificador(metodoKey);

                if (metodoInfo == null || !metodoInfo.modificador.equals("metodillo")) {
                    agregarError("Método '" + nombre + "' no existe en clase '" + tipoClase + "' (línea " + token.line + ")");
                }
            }

            // Limpiar contexto de invoco
            acabaDeVerInvoco = false;
            objetoInvocando = "";
            return;
        }

        // CONTEXTO 5: Nombre de función
        if (!tipoRetornoActual.isEmpty() && funcionActual.isEmpty() && claseActual.isEmpty()) {
            registrarFuncion(nombre, token.line);
            return;
        }

        // CONTEXTO 6: Nombre de método
        if (!tipoRetornoActual.isEmpty() && !claseActual.isEmpty() && funcionActual.isEmpty()) {
            funcionActual = nombre;
            tipoActual = ""; // Limpiar para evitar que se registre como parámetro
            idPendiente = ""; // Limpiar para evitar que se registre como variable
            return;
        }

        // CONTEXTO 7: Parámetro
        if ((!funcionActual.isEmpty() || dentroDeConstructor) && !tipoActual.isEmpty() && !dentroDeFuncion) {
            registrarParametro(nombre, token.line);
            return;
        }

        // CONTEXTO 8: Declarando variable
        if (!tipoActual.isEmpty() && idPendiente.isEmpty()) {
            idPendiente = nombre;
            return;
        }

        // CONTEXTO 9: Usando variable
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

        // Registrar función inmediatamente
        IdentificadorInfo info = new IdentificadorInfo(nombre, tipoRetornoActual, null, "favor", linea, "global");
        tablaSimbolos.put(key, info);

        funcionActual = nombre;

        // CRÍTICO: Limpiar residuos
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

        // Agregar parámetros
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
            acumulador = 0;
            expresionTieneReales = false;
            tipoExpresionActual = "";  // Limpiar tipo de expresión
            operadorActual = "+";
            dentroDeExpresion = true;
        }
    }

    private void ejecutarAsignacionConAcumulador(int linea) {
        if (varAsignando == null) return;

        // VALIDACIÓN DE COMPATIBILIDAD DE TIPOS
        String tipoVariable = varAsignando.tipo;
        String tipoExpresion = tipoExpresionActual.isEmpty() ?
                (expresionTieneReales ? "realito" : "enterito") :
                tipoExpresionActual;

        boolean hayError = false;

        // ===== VALIDACIONES POR TIPO DE VARIABLE =====

        if (tipoVariable.equals("enterito")) {
            // enterito solo acepta enterito
            if (tipoExpresion.equals("realito")) {
                agregarError("No se puede asignar REALITO a variable ENTERITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("cadenita")) {
                agregarError("No se puede asignar CADENITA a variable ENTERITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("charsito")) {
                agregarError("No se puede asignar CHARSITO a variable ENTERITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("booleanito")) {
                agregarError("No se puede asignar BOOLEANITO a variable ENTERITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            }
        } else if (tipoVariable.equals("realito")) {
            // realito acepta enterito (con warning) y realito
            if (tipoExpresion.equals("cadenita")) {
                agregarError("No se puede asignar CADENITA a variable REALITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("charsito")) {
                agregarError("No se puede asignar CHARSITO a variable REALITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("booleanito")) {
                agregarError("No se puede asignar BOOLEANITO a variable REALITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("enterito")) {
                // Conversión permitida con warning
                agregarWarning("Conversión implícita: ENTERITO → REALITO en '" + nombreVarAsignando + "'");
            }
        } else if (tipoVariable.equals("cadenita")) {
            // cadenita acepta cadenita y charsito
            if (tipoExpresion.equals("enterito")) {
                agregarError("No se puede asignar ENTERITO a variable CADENITA '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("realito")) {
                agregarError("No se puede asignar REALITO a variable CADENITA '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("booleanito")) {
                agregarError("No se puede asignar BOOLEANITO a variable CADENITA '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            }
            // charsito → cadenita es OK (sin warning)
        } else if (tipoVariable.equals("charsito")) {
            // charsito acepta charsito y cadenita (toma primer carácter)
            if (tipoExpresion.equals("enterito")) {
                agregarError("No se puede asignar ENTERITO a variable CHARSITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("realito")) {
                agregarError("No se puede asignar REALITO a variable CHARSITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            } else if (tipoExpresion.equals("booleanito")) {
                agregarError("No se puede asignar BOOLEANITO a variable CHARSITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            }
            // cadenita → charsito es OK (toma primer carácter, sin warning)
        } else if (tipoVariable.equals("booleanito")) {
            // booleanito solo acepta booleanito
            if (!tipoExpresion.equals("booleanito")) {
                agregarError("No se puede asignar " + tipoExpresion.toUpperCase() + " a variable BOOLEANITO '" + nombreVarAsignando + "' (línea " + linea + ")");
                hayError = true;
            }
        }

        // Si hay error, limpiar y salir SIN asignar
        if (hayError) {
            limpiarAsignacion();
            return;
        }

        // Asignar valor (solo si es numérico)
        if ((tipoVariable.equals("enterito") || tipoVariable.equals("realito")) &&
                (tipoExpresion.equals("enterito") || tipoExpresion.equals("realito"))) {
            if (tipoVariable.equals("enterito")) {
                varAsignando.valor = (int) acumulador;
                imprimirAccionSemantica("Asignando: " + nombreVarAsignando + " = " + (int) acumulador);
            } else {
                varAsignando.valor = acumulador;
                imprimirAccionSemantica("Asignando: " + nombreVarAsignando + " = " + acumulador);
            }
        }

        varAsignando.inicializada = true;
        limpiarAsignacion();
    }

    private void limpiarAsignacion() {
        varAsignando = null;
        nombreVarAsignando = "";
        dentroDeExpresion = false;
        acumulador = 0;
        expresionTieneReales = false;
        tipoExpresionActual = "";
    }

    private float aplicarOperacion(float acumulado, float valor, String operador) {
        switch (operador) {
            case "+": return acumulado + valor;
            case "-": return acumulado - valor;
            case "*": return acumulado * valor;
            case "/": return valor != 0 ? acumulado / valor : 0;
            default: return acumulado + valor;
        }
    }

    private void procesarCierreBloque() {
        nivelBloque--; // Decrementar nivel de bloque global

        // Si estamos en una función o constructor, decrementar su nivel
        if ((!funcionActual.isEmpty() || dentroDeConstructor) && nivelBloqueFuncion > 0) {
            nivelBloqueFuncion--;
        }

        // Solo validar retorno y limpiar contexto cuando salimos del bloque principal
        if (nivelBloqueFuncion == 0) {
            // Constructor terminado
            if (dentroDeConstructor) {
                dentroDeConstructor = false;
                funcionActual = ""; // Limpiar residuos
                tipoRetornoActual = "";
                parametrosActuales.clear();
                dentroDeFuncion = false;
            }
            // Función/Método terminado
            else if (!funcionActual.isEmpty()) {
                if (!tipoRetornoActual.equals("vacio") && !funcionTieneRetorno) {
                    agregarError("Función '" + funcionActual + "' debe tener 'retorna'");
                }

                // Si estamos en una clase, registrar como método
                if (!claseActual.isEmpty()) {
                    registrarMetodo(tokens.get(currentTokenIndex - 1)); // Token del '}'
                } else {
                    // Actualizar parámetros de la función global
                    if (!funcionActual.equals("principalsito")) {
                        String key = "global." + funcionActual;
                        IdentificadorInfo funcInfo = tablaSimbolos.get(key);
                        if (funcInfo != null) {
                            // Actualizar parámetros
                            funcInfo.tiposParametros.clear();
                            funcInfo.nombresParametros.clear();
                            for (ParametroInfo param : parametrosActuales) {
                                funcInfo.tiposParametros.add(param.tipo);
                                funcInfo.nombresParametros.add(param.nombre);
                            }
                        }
                    }
                }

                // Limpiar todo el contexto de la función/método
                funcionActual = "";
                tipoRetornoActual = "";
                funcionTieneRetorno = false;
                parametrosActuales.clear();
                dentroDeFuncion = false;
                dentroDeMetodo = false; // Desactivar flag de método

                // Limpiar residuos que pudieran causar registro incorrecto
                idPendiente = "";
                tipoActual = "";
                dentroDeFuncion = false;
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
            // Si ya superamos 70 caracteres, truncar
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
        // Convertir pila a lista para mostrar correctamente ($ a la izquierda, tope a la derecha)
        List<String> elementos = new ArrayList<>(pila);
        Collections.reverse(elementos);

        String pilaStr = String.join(" ", elementos);

        // Si es muy larga (más de 35 caracteres), truncar con ...
        if (pilaStr.length() > 35) {
            pilaStr = pilaStr.substring(0, 32) + "...";
        }

        return pilaStr;
    }

    private void agregarError(String mensaje) {
        // Crear clave única para evitar duplicados
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

        // TABLA DE IDENTIFICADORES
        System.out.println("\n📋 IDENTIFICADORES DECLARADOS:");
        System.out.println("-".repeat(140));
        System.out.printf("%-20s %-15s %-20s %-15s %-30s%n",
                "NOMBRE", "TIPO", "VALOR", "MODIFICADOR", "SCOPE");
        System.out.println("-".repeat(140));

        if (tablaSimbolos.isEmpty()) {
            System.out.println(" (No se declararon identificadores)");
        } else {
            // Ordenar por scope y nombre
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

        // ERRORES
        if (!erroresSemanticos.isEmpty()) {
            System.out.println("\n❌ ERRORES SEMÁNTICOS ENCONTRADOS:");
            System.out.println("-".repeat(140));
            for (String error : erroresSemanticos) {
                System.out.println("   " + error);
            }
        }

        // WARNINGS
        if (!warnings.isEmpty()) {
            System.out.println("\n⚠️  ADVERTENCIAS:");
            System.out.println("-".repeat(140));
            for (String warning : warnings) {
                System.out.println("   " + warning);
            }
        }

        // RESUMEN
        System.out.println("\n📊 RESUMEN:");
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