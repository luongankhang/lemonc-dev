package site.ilemon.semantic;

import site.ilemon.ast.Ast;
import site.ilemon.ast.Ast.Type.TypeKind;
import site.ilemon.exception.SemanticException;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.diagnostic.DiagnosticCodes;
import site.ilemon.diagnostic.DiagnosticEngine;
import site.ilemon.visitor.ISemanticVisitor;
import site.ilemon.type.TypeRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.nio.file.Path;


/**
 * Semantic analysis visitor.
 *
 * <p>Traverses the AST using the Visitor pattern to perform static semantic checks:</p>
 * <ul>
 *   <li><b>Type checking</b>: type consistency in assignments, operations, and method calls</li>
 *   <li><b>Variable checking</b>: undeclared variables, variables used before assignment</li>
 *   <li><b>Method checking</b>: undefined methods, duplicate definitions, parameter count/type matching</li>
 *   <li><b>Control flow checking</b>: if/while conditions must be bool</li>
 *   <li><b>Return value checking</b>: return type matches declaration, main method must be void</li>
 * </ul>
 *
 * <p>Throws {@link SemanticException} when errors are found.</p>
 *
 * @author andy
 * @see site.ilemon.visitor.ISemanticVisitor
 * @see SemanticException
 */
public class SemanticVisitor implements ISemanticVisitor {

    private boolean pass = true;

    private final boolean collectErrors;
    private final DiagnosticEngine diagnosticEngine = new DiagnosticEngine();

    private final ArrayList<String> errors = new ArrayList<>();

    private final ArrayList<Integer> errorLineNumbers = new ArrayList<>();

    private Ast.Type.T currType;

    private String currMethodName;

    private HashMap<String,MethodVarTable> methodVarTable;

    private HashMap<String,Ast.Type.T> methodNameRetTypeMap;

    private HashSet<String> currMethodLocalVar;

    private int loopDepth = 0;

    private HashMap<String,Ast.Method.MethodSingle> methodMap;

    /** Global constants visible in the current program (incl. re-exported {@code alias_NAME} copies). */
    private HashMap<String, Ast.ConstDecl> globalConsts = new HashMap<>();
    /** Identity guard so a shared imported-module constant is validated once. */
    private java.util.Set<Ast.ConstDecl> validatedConsts = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private Ast.Type.T typeOfMethodDeclared;

    private HashSet<String> importedModuleNames = new HashSet<>();
    private ScopeManager scopeManager = new ScopeManager();

    public SemanticVisitor(){
        this(false);
    }

    private SemanticVisitor(boolean collectErrors){

        this.collectErrors = collectErrors;
        this.methodVarTable = new HashMap<>();
        this.methodMap = new HashMap<>();
        this.methodNameRetTypeMap = new HashMap<>();
    }

    public static SemanticVisitor collecting() {
        return new SemanticVisitor(true);
    }

    public DiagnosticEngine getDiagnosticEngine() {
        return diagnosticEngine;
    }

    public java.util.List<Diagnostic> getDiagnostics() {
        return diagnosticEngine.diagnostics();
    }

    public ArrayList<String> getErrors() {
        return new ArrayList<>(this.errors);
    }

    public ArrayList<Integer> getErrorLineNumbers() {
        return new ArrayList<>(this.errorLineNumbers);
    }

    public boolean passOrNot(){
        return pass;
    }

    private Ast.Type.T unknownType() {
        return new Ast.Type.Int();
    }

    private String typeName(Ast.Type.T type) {
        if (type == null) {
            return "unknown";
        }
        String name = type.toString();
        return name.startsWith("@") ? name.substring(1) : name;
    }

    private void checkSameOperandTypes(int lineNum, String operator, Ast.Type.T leftType, Ast.Type.T rightType) {
        if (isArrayType(leftType) || isArrayType(rightType)) {
            error(lineNum, String.format(
                    "operator '%s' does not support array operands: left is %s, right is %s",
                    operator, typeName(leftType), typeName(rightType)));
            this.currType = unknownType();
            return;
        }
        Ast.Type.T promoted = promoteNumeric(leftType, rightType);
        if (promoted != null) {
            this.currType = promoted;
            return;
        }
        if (!isMatch(leftType, rightType)) {
            typeError(DiagnosticCodes.TYPE_OPERATOR, typeName(leftType), typeName(rightType), "binary expression",
                    lineNum, null, "operator '" + operator + "'", "operand types must match or use a supported numeric conversion");
        }
        this.currType = leftType;
    }

    private void checkBooleanOperandTypes(int lineNum, String operator, Ast.Type.T leftType, Ast.Type.T rightType) {
        if (leftType == null || rightType == null ||
                leftType.getKind() != TypeKind.BOOL || rightType.getKind() != TypeKind.BOOL) {
            typeError(DiagnosticCodes.TYPE_OPERATOR, "bool", typeName(leftType) + " and " + typeName(rightType),
                    "binary expression", lineNum, null, "operator '" + operator + "'", "use boolean operands");
        }
        this.currType = new Ast.Type.Bool();
    }

    @Override
    public void visit(Ast.Expr.Add obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        checkSameOperandTypes(obj.getLineNum(), "+", leftType, this.currType);
    }

    @Override
    public void visit(Ast.Expr.And obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        checkBooleanOperandTypes(obj.getLineNum(), "&&", leftType, this.currType);
    }

    @Override
    public void visit(Ast.Type.Bool obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.Byte obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.Short obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.Char obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.Long obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Stmt.Assign obj) {
        if (importedModuleNames.contains(obj.getId().getId())) {
            semanticError(DiagnosticCodes.SEM_INVALID_SYMBOL_USAGE,
                    "cannot assign to imported module '" + obj.getId().getId() + "'",
                    obj.getLineNum(), obj.getSpan(), "immutable module binding",
                    "module imports are compile-time bindings", null);
            return;
        }
        MethodVarTable assignTable = this.methodVarTable.get(currMethodName);
        boolean isLocalTarget = assignTable != null && assignTable.get(obj.getId().getId()) != null;
        if (!isLocalTarget && resolveConst(obj.getId().getId()) != null) {
            semanticError(DiagnosticCodes.SEM_CONST_IMMUTABLE,
                    "cannot assign to constant '" + obj.getId().getId() + "': constants are immutable",
                    obj.getLineNum(), obj.getSpan(), "immutable constant",
                    "constants cannot be reassigned after declaration", null);
            return;
        }
        if(obj.getExpr() instanceof Ast.Expr.T){
            this.visit((Ast.Expr.T)obj.getExpr());
            Ast.Type.T exprType = null;
            if( obj.getExpr() instanceof Ast.Expr.Call)
                exprType = ((Ast.Expr.Call) obj.getExpr()).getReturnType();
            else
                exprType = this.currType;
            if( this.currMethodLocalVar.contains(obj.getId().getId()))
                this.currMethodLocalVar.remove(obj.getId().getId());
            this.visit(obj.getId());
            Ast.Type.T targetType = this.currType;
            if (isArrayType(this.currType) || isArrayType(exprType)) {
                typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(targetType), typeName(exprType),
                        expressionName(obj.getExpr()), obj.getLineNum(), obj.getSpan(),
                        "array assignment", "arrays cannot be assigned as whole values");
                return;
            }
            if (!isAssignable(targetType, exprType, obj.getExpr())) {
                if (!rangeErrorIfNeeded(targetType, exprType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                        "assignment to '" + obj.getId().getId() + "'")) {
                    if (!shortRangeErrorIfNeeded(targetType, exprType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                            "assignment to '" + obj.getId().getId() + "'")) {
                        typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(targetType), typeName(exprType),
                                expressionName(obj.getExpr()), obj.getLineNum(), obj.getSpan(),
                                "assignment to '" + obj.getId().getId() + "'", null);
                    }
                }
            }
        }

    }

    @Override
    public void visit(Ast.Stmt.Block obj) {
        scopeManager.enterScope();
        for( Ast.Stmt.T stmt : obj.getStmts()){
            this.visit(stmt);
        }
        scopeManager.exitScope();
    }

    @Override
    public void visit(Ast.Stmt.Import obj) {
        Ast.ImportDecl declaration = obj.getDeclaration();
        importedModuleNames.add(declaration.getName());
        try {
            scopeManager.declareImport(declaration.getName(), Path.of(declaration.getPath()).toAbsolutePath().normalize());
        } catch (IllegalArgumentException duplicate) {
            semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION, duplicate.getMessage(),
                    obj.getLineNum(), obj.getSpan(), "duplicate import", "use a different module binding name", null);
        }
    }

    @Override
    public void visit(Ast.Expr.Call obj) {
        validateImportBinding(obj.getName(), obj.getLineNum(), obj.getSpan());
        Ast.Type.T returnType = validateMethodCall(obj.getName(), obj.getInputParams(), obj.getLineNum(), obj.getSpan());
        if (returnType.getKind() == TypeKind.VOID) {
            semanticError(DiagnosticCodes.SEM_INVALID_SYMBOL_USAGE, "void method '" + obj.getName()
                    + "' cannot be used as an expression", obj.getLineNum(), obj.getSpan(),
                    "void function used as value", "call a non-void function instead", null);
        }
        obj.setReturnType(returnType);
        this.currType = returnType;
    }

    @Override
    public void visit(Ast.Declare.T obj) {
        if (obj instanceof Ast.Declare.DeclareSingle) {
            this.currType = ((Ast.Declare.DeclareSingle) obj).getType();
        }
    }

    @Override
    public void visit(Ast.Expr.Div obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        checkSameOperandTypes(obj.getLineNum(), "/", leftType, this.currType);
    }

    @Override
    public void visit(Ast.Expr.Mod obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        Ast.Type.T rightType = this.currType;
        if (leftType == null || rightType == null
                || !isIntegerLike(leftType) || !isIntegerLike(rightType)) {
            typeError(DiagnosticCodes.TYPE_OPERATOR, "int or byte", typeName(leftType) + " and " + typeName(rightType),
                    expressionName(obj), obj.getLineNum(), obj.getSpan(), "operator '%'", "the remainder operator requires int operands");
        }
        this.currType = new Ast.Type.Int();
    }

    @Override
    public void visit(Ast.Type.Float obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.Double obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Expr obj) {

    }

    @Override
    public void visit(Ast.Expr.GT obj) {
        checkOrderComparison(obj.getLeft(), obj.getRight(), ">", obj.getLineNum());
    }

    @Override
    public void visit(Ast.Expr.Id obj) {
        MethodVarTable mTable = this.methodVarTable.get(currMethodName);
        if( mTable == null )
            internalError(obj.getLineNum(), "internal error: variable table for method '" + currMethodName + "' was not found");
        if (mTable == null) {
            this.currType = unknownType();
            obj.setType(this.currType);
            return;
        }
        if( mTable.get(obj.getId()) == null ){
            // Not a local: resolve against global constants (locals shadow constants).
            Ast.ConstDecl constant = resolveConst(obj.getId());
            if (constant != null) {
                obj.setType(constant.getType());
                this.currType = constant.getType();
                return;
            }
            int separator = obj.getId().indexOf('_');
            if (separator > 0 && importedModuleNames.contains(obj.getId().substring(0, separator))) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                        "module import '" + obj.getId().substring(0, separator)
                                + "' has no public constant '" + obj.getId().substring(separator + 1) + "'",
                        obj.getLineNum(), obj.getSpan(), "unknown constant",
                        "private constants are not accessible from other modules", null);
                this.currType = unknownType();
                obj.setType(this.currType);
                return;
            }
            semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined variable: " + obj.getId(),
                    obj.getLineNum(), obj.getSpan(), "unknown variable",
                    "the name is not declared in the current method scope",
                    nearestName(obj.getId(), mTable.names()));
            this.currType = unknownType();
            obj.setType(this.currType);
            return;
        }
        if( currMethodLocalVar.contains(obj.getId()))
            error(obj.getLineNum(), String.format("variable '%s' may be used before assignment", obj.getId()));
        if( obj.getType() == null ) {
            // Type may not have been resolved during Parser phase (variable not registered in varTable)
            obj.setType(mTable.get(obj.getId()));
        }
        this.currType = obj.getType();
    }

    /**
     * Resolves a name to a global constant: first the constants of the module that
     * declares the current method (so re-exported bodies see private module
     * constants), then the program's own constants (including {@code alias_NAME}
     * re-exports from imports). Locals are resolved by the caller first.
     */
    private Ast.ConstDecl resolveConst(String name) {
        Ast.Method.MethodSingle method = this.methodMap.get(currMethodName);
        if (method != null && method.getModuleConsts() != null) {
            for (Ast.ConstDecl constant : method.getModuleConsts()) {
                if (constant.getId().equals(name)) {
                    return constant;
                }
            }
        }
        return globalConsts.get(name);
    }

    /** Validates one global constant declaration and resolves its literal value. */
    private void visitConstDecl(Ast.ConstDecl constant) {
        if (constant == null || !validatedConsts.add(constant)) {
            return;
        }
        Ast.Type.T type = constant.getType();
        if (type == null || isArrayType(type) || type.getKind() == TypeKind.VOID) {
            semanticError(DiagnosticCodes.SEM_CONST_INITIALIZER,
                    "constant '" + constant.getId() + "' must have a scalar type",
                    constant.getLineNum(), constant.getSpan(), "invalid constant type",
                    "constants cannot be arrays or void", null);
            return;
        }
        Ast.Expr.T initializer = constant.getInitializer();
        if (!isLiteralInitializer(initializer)) {
            semanticError(DiagnosticCodes.SEM_CONST_INITIALIZER,
                    "constant '" + constant.getId() + "' must be initialized with a literal value",
                    constant.getLineNum(), constant.getSpan(), "non-literal initializer",
                    "constant initializers must be compile-time literals", null);
            return;
        }
        this.visit(initializer);
        Ast.Type.T initializerType = this.currType;
        if (!isAssignable(type, initializerType, initializer)) {
            if (!rangeErrorIfNeeded(type, initializerType, initializer, constant.getLineNum(),
                    constant.getSpan(), "constant initializer for '" + constant.getId() + "'")
                    && !shortRangeErrorIfNeeded(type, initializerType, initializer, constant.getLineNum(),
                    constant.getSpan(), "constant initializer for '" + constant.getId() + "'")) {
                typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(type), typeName(initializerType),
                        expressionName(initializer), constant.getLineNum(), constant.getSpan(),
                        "constant initializer for '" + constant.getId() + "'", null);
            }
        }
        constant.setResolvedValue(resolveLiteralValue(initializer));
    }

    /**
     * True for scalar literals and the parser-generated unary-minus form
     * {@code 0 - <number>} so negative constants like {@code -30000} qualify.
     */
    private boolean isLiteralInitializer(Ast.Expr.T initializer) {
        if (initializer instanceof Ast.Expr.Number
                || initializer instanceof Ast.Expr.True
                || initializer instanceof Ast.Expr.False
                || initializer instanceof Ast.Expr.Str) {
            return true;
        }
        if (initializer instanceof Ast.Expr.Sub sub
                && sub.getLeft() instanceof Ast.Expr.Number zero
                && "0".equals(String.valueOf(zero.getValue()))) {
            return sub.getRight() instanceof Ast.Expr.Number;
        }
        return false;
    }

    /** Extracts the literal text of a validated constant initializer. */
    private String resolveLiteralValue(Ast.Expr.T initializer) {
        if (initializer instanceof Ast.Expr.True) {
            return "true";
        }
        if (initializer instanceof Ast.Expr.False) {
            return "false";
        }
        if (initializer instanceof Ast.Expr.Str) {
            return ((Ast.Expr.Str) initializer).getValue();
        }
        if (initializer instanceof Ast.Expr.Number number) {
            return String.valueOf(number.getValue());
        }
        if (initializer instanceof Ast.Expr.Sub sub) {
            String right = resolveLiteralValue(sub.getRight());
            return right == null ? null : "-" + right;
        }
        return null;
    }

    private boolean statementTerminates(Ast.Stmt.T statement) {
        if (statement == null) {
            return false;
        }
        if (statement instanceof Ast.Stmt.Return) {
            return true;
        }
        if (statement instanceof Ast.Stmt.Break || statement instanceof Ast.Stmt.Continue) {
            return true;
        }
        if (statement instanceof Ast.Stmt.Block block) {
            if (block.getStmts() == null || block.getStmts().isEmpty()) {
                return false;
            }
            for (Ast.Stmt.T stmt : block.getStmts()) {
                if (statementTerminates(stmt)) {
                    return true;
                }
                if (!(stmt instanceof Ast.Stmt.If) && !(stmt instanceof Ast.Stmt.Block) && !(stmt instanceof Ast.Stmt.While) && !(stmt instanceof Ast.Stmt.For)) {
                    return false;
                }
            }
            return false;
        }
        if (statement instanceof Ast.Stmt.If ifStmt) {
            if (ifStmt.getElseStmt() == null) {
                return false;
            }
            boolean thenTerminates = statementTerminates(ifStmt.getThenStmt());
            boolean elseTerminates = statementTerminates(ifStmt.getElseStmt());
            return thenTerminates && elseTerminates;
        }
        return false;
    }

    @Override
    public void visit(Ast.Stmt.If obj) {
        this.visit(obj.getCondition());
        if (this.currType.getKind() != TypeKind.BOOL)
            typeError(DiagnosticCodes.TYPE_CONDITION, "bool", typeName(this.currType), expressionName(obj.getCondition()),
                    obj.getCondition().getLineNum(), obj.getCondition().getSpan(), "if condition", null);

        HashSet<String> before = new HashSet<>(this.currMethodLocalVar);
        this.currMethodLocalVar = new HashSet<>(before);
        this.visit(obj.getThenStmt());
        HashSet<String> thenUnassigned = new HashSet<>(this.currMethodLocalVar);
        boolean thenTerminates = statementTerminates(obj.getThenStmt());

        if (obj.getElseStmt() != null) {
            this.currMethodLocalVar = new HashSet<>(before);
            this.visit(obj.getElseStmt());
            HashSet<String> elseUnassigned = new HashSet<>(this.currMethodLocalVar);
            boolean elseTerminates = statementTerminates(obj.getElseStmt());

            if (thenTerminates && elseTerminates) {
                this.currMethodLocalVar = new HashSet<>(before);
            } else if (thenTerminates) {
                this.currMethodLocalVar = new HashSet<>(elseUnassigned);
            } else if (elseTerminates) {
                this.currMethodLocalVar = new HashSet<>(thenUnassigned);
            } else {
                thenUnassigned.addAll(elseUnassigned);
                this.currMethodLocalVar = thenUnassigned;
            }
        } else {
            this.currMethodLocalVar = before;
        }
    }

    @Override
    public void visit(Ast.Type.Int obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Program.T programSingle) {
        this.visit(((Ast.Program.ProgramSingle)programSingle).getMainClass());
    }

    @Override
    public void visit(Ast.Expr.LT obj) {
        checkOrderComparison(obj.getLeft(), obj.getRight(), "<", obj.getLineNum());
    }

    @Override
    public void visit(Ast.Expr.LTE obj) {
        checkOrderComparison(obj.getLeft(), obj.getRight(), "<=", obj.getLineNum());
    }

    @Override
    public void visit(Ast.Expr.GTE obj) {
        checkOrderComparison(obj.getLeft(), obj.getRight(), ">=", obj.getLineNum());
    }

    @Override
    public void visit(Ast.Expr.EQ obj) {
        checkComparison(obj.getLeft(), obj.getRight(), "==", obj.getLineNum());
    }

    @Override
    public void visit(Ast.Expr.NEQ obj) {
        checkComparison(obj.getLeft(), obj.getRight(), "!=", obj.getLineNum());
    }

    @Override
    public void visit(Ast.MainClass.T obj) {
        Ast.MainClass.MainClassSingle mainClassSingle = (Ast.MainClass.MainClassSingle) obj;
        scopeManager = new ScopeManager();
        importedModuleNames.clear();
        for (Ast.ImportDecl importDecl : mainClassSingle.getImports()) {
            importedModuleNames.add(importDecl.getName());
            try {
                scopeManager.declareImport(importDecl.getName(), Path.of(importDecl.getPath()).toAbsolutePath().normalize());
            } catch (IllegalArgumentException duplicate) {
                semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION, duplicate.getMessage(),
                        importDecl.getSpan() == null ? 0 : importDecl.getSpan().getStartLine(), importDecl.getSpan(),
                        "duplicate import", "use a different module binding name", null);
            }
        }
        for(int i = 0; i < mainClassSingle.getMethods().size(); i++){
            Ast.Method.MethodSingle method = (Ast.Method.MethodSingle) mainClassSingle.getMethods().get(i);
            if( methodMap.containsKey(method.getId())){
                semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION, "duplicate method declaration: " + method.getId(),
                        method.getLineNum(), method.getSpan(), "duplicate method", "the method was declared earlier", null);
            }else{
                methodMap.put(method.getId(),method);
                methodNameRetTypeMap.put(method.getId(),method.getRetType());
            }
        }
        // Global constants: register, check duplicates, and validate initializers.
        globalConsts.clear();
        validatedConsts.clear();
        for (Ast.ConstDecl constant : mainClassSingle.getConstants()) {
            if (globalConsts.containsKey(constant.getId()) || methodMap.containsKey(constant.getId())) {
                semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION,
                        "duplicate constant declaration: " + constant.getId(),
                        constant.getLineNum(), constant.getSpan(), "duplicate constant",
                        "the constant was declared earlier", null);
            } else {
                globalConsts.put(constant.getId(), constant);
                visitConstDecl(constant);
            }
        }
        // Constants reachable through re-exported methods (their declaring module's
        // table) must also be validated; they stay out of globalConsts so private
        // module constants are never resolvable by bare name from this module.
        for (Ast.Method.T node : mainClassSingle.getMethods()) {
            Ast.Method.MethodSingle method = (Ast.Method.MethodSingle) node;
            if (method.getModuleConsts() != null) {
                for (Ast.ConstDecl constant : method.getModuleConsts()) {
                    visitConstDecl(constant);
                }
            }
        }
        validateMainMethod();
        for(int i = 0; i < mainClassSingle.getMethods().size(); i++){
            Ast.Method.MethodSingle method = (Ast.Method.MethodSingle) mainClassSingle.getMethods().get(i);
            this.visit(method);
        }
    }

    private void validateMainMethod() {
        Ast.Method.MethodSingle main = this.methodMap.get("main");
        if (main == null) {
            error(1, "program must define void main()");
        }
        if (main == null) {
            return;
        }
        if (main.getRetType().getKind() != TypeKind.VOID) {
            error(main.getLineNum(), "main method return type must be void, but is declared as: "
                    + typeName(main.getRetType()));
        }
        if (main.getFormals() != null && !main.getFormals().isEmpty()) {
            error(main.getLineNum(), "main method cannot declare parameters; it must be void main()");
        }
    }

    @Override
    public void visit(Ast.Method.MethodSingle obj) {
        scopeManager.enterScope();
        MethodVarTable mTable = new MethodVarTable(diagnosticEngine);
        this.currMethodLocalVar = new HashSet<>();
        for( Ast.Declare.T dec : obj.getLocals()){
            Ast.Declare.DeclareSingle declareSingle = (Ast.Declare.DeclareSingle) dec;
            if (!isArrayType(declareSingle.getType())) {
                this.currMethodLocalVar.add(declareSingle.getId());
            }
        }

        mTable.put(obj.getFormals(),obj.getLocals());
        this.methodVarTable.put(obj.getId(),mTable);
        this.currMethodName = obj.getId();
        this.typeOfMethodDeclared = obj.getRetType();

        if( obj.getId().equals("main")){
            if( obj.getRetType().getKind() != TypeKind.VOID)
                error(obj.getLineNum(), "main method return type must be void, but is declared as: " + typeName(obj.getRetType()));
        }
        for( int i = 0; i < obj.getStms().size(); i++){
            Ast.Stmt.T stmt = obj.getStms().get(i);
            this.visit(stmt);
        }
        if( !obj.getId().equals("main")
                && obj.getRetType().getKind() != TypeKind.VOID
                && !statementsMustReturn(obj.getStms()) ){
            error(obj.getLineNum(), "non-void method '" + obj.getId() + "' does not return on all paths");
        }
        scopeManager.exitScope();
    }

    private void validateImportBinding(String methodName, int line, site.ilemon.util.SourceSpan span) {
        int separator = methodName.indexOf('_');
        if (separator > 0 && scopeManager.resolveImport(methodName.substring(0, separator)) == null
                && importedModuleNames.contains(methodName.substring(0, separator))) {
            semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                    "module import '" + methodName.substring(0, separator) + "' is not visible in this scope",
                    line, span, "import is out of scope", "declare the import in this lexical scope", null);
        }
    }

    @Override
    public void visit(Ast.Expr.Mul obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        checkSameOperandTypes(obj.getLineNum(), "*", leftType, this.currType);


    }

    @Override
    public void visit(Ast.Expr.Number obj) {
        if(obj.getType() instanceof Ast.Type.Int){
            this.currType = new Ast.Type.Int();
        }else if(obj.getType() instanceof Ast.Type.Float){
            this.currType = new Ast.Type.Float();
        }else if(obj.getType() instanceof Ast.Type.Long){
            this.currType = new Ast.Type.Long();
        }else if(obj.getType() instanceof Ast.Type.Char){
            this.currType = new Ast.Type.Char();
        }else if(obj.getType() instanceof Ast.Type.Double){
            this.currType = new Ast.Type.Double();
        }else{
            // Unsupported numeric type
            error(obj.getLineNum(), "unsupported numeric type: " + typeName(obj.getType()));
        }
    }

    @Override
    public void visit(Ast.Expr.Or obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        checkBooleanOperandTypes(obj.getLineNum(), "||", leftType, this.currType);
    }



    @Override
    public void visit(Ast.Type.Str obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Expr.Sub obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        checkSameOperandTypes(obj.getLineNum(), "-", leftType, this.currType);
    }

    @Override
    public void visit(Ast.Type obj) {

    }

    @Override
    public void visit(Ast.Type.Void obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Stmt.T obj) {
        obj.accept(this);
    }

    @Override
    public void visit(Ast.Stmt.Printf obj) {
        ArrayList<Character> placeholders = printfPlaceholders(obj.getFormat(), obj.getLineNum());
        int argCount = obj.getExprs() == null ? 0 : obj.getExprs().size();
        if (placeholders.size() != argCount) {
            error(obj.getLineNum(), String.format(
                    "printf argument count mismatch: format string requires %d, but found %d",
                    placeholders.size(), argCount));
        }
        for (int i = 0; i < argCount; i++) {
            Ast.Expr.T expr = obj.getExprs().get(i);
            this.visit(expr);
            char placeholder = placeholders.get(i);
            if (placeholder == 'd' && !isIntegerLike(this.currType)) {
                typeError(DiagnosticCodes.TYPE_FORMAT, "int or byte", typeName(this.currType), expressionName(expr),
                        expr.getLineNum(), expr.getSpan(), "printf %d argument", null);
            }
            if (placeholder == 'f'
                    && this.currType.getKind() != TypeKind.FLOAT
                    && this.currType.getKind() != TypeKind.DOUBLE) {
                typeError(DiagnosticCodes.TYPE_FORMAT, "float or double", typeName(this.currType), expressionName(expr),
                        expr.getLineNum(), expr.getSpan(), "printf %f argument", null);
            }
        }
    }

    @Override
    public void visit(Ast.Stmt.PrintLine obj) {

    }

    @Override
    public void visit(Ast.Expr.T obj) {
        obj.accept(this);
    }

    @Override
    public void visit(Ast.Expr.True obj) {
        this.currType = new Ast.Type.Bool();
    }

    @Override
    public void visit(Ast.Expr.False obj) {
        this.currType = new Ast.Type.Bool();
    }

    @Override
    public void visit(Ast.Expr.Not obj) {
        this.visit(obj.getExpr());
        if( this.currType.getKind() != TypeKind.BOOL)
            typeError(DiagnosticCodes.TYPE_OPERATOR, "bool", typeName(this.currType), expressionName(obj.getExpr()),
                    obj.getLineNum(), obj.getSpan(), "operator '!'", "use a boolean expression");
        this.currType = new Ast.Type.Bool();
    }

    @Override
    public void visit(Ast.Expr.Str obj) {
        this.currType = new Ast.Type.Str();
    }

    @Override
    public void visit(Ast.Type.T obj) {

    }

    @Override
    public void visit(Ast.Stmt.Return obj) {
        if( "main".equals(this.currMethodName) ){
            error(obj.getLineNum(), "main method does not allow return statements");
        }
        if( this.typeOfMethodDeclared != null
                && this.typeOfMethodDeclared.getKind() == TypeKind.VOID ){
            error(obj.getLineNum(), "void method cannot return a value");
        }
        this.visit(obj.getExpr());
        if (!isAssignable(typeOfMethodDeclared, this.currType, obj.getExpr())) {
            if (!rangeErrorIfNeeded(typeOfMethodDeclared, this.currType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                    "return statement")) {
                typeError(DiagnosticCodes.TYPE_RETURN, typeName(typeOfMethodDeclared), typeName(this.currType),
                        expressionName(obj.getExpr()), obj.getLineNum(), obj.getSpan(), "return statement", null);
            }
        }
    }


    @Override
    public void visit(Ast.Stmt.While obj) {
        this.visit(obj.getCondition());
        if( this.currType.getKind() != TypeKind.BOOL )
            typeError(DiagnosticCodes.TYPE_CONDITION, "bool", typeName(this.currType), expressionName(obj.getCondition()),
                    obj.getCondition().getLineNum(), obj.getCondition().getSpan(), "while condition", null);
        HashSet<String> before = new HashSet<>(this.currMethodLocalVar);
        loopDepth++;
        this.currMethodLocalVar = new HashSet<>(before);
        this.visit(obj.getBody());
        loopDepth--;
        this.currMethodLocalVar = before;
    }

    @Override
    public void visit(Ast.Stmt.For obj) {
        if (obj.getInit() != null) {
            this.visit(obj.getInit());
        }
        this.visit(obj.getCondition());
        if( this.currType.getKind() != TypeKind.BOOL )
            typeError(DiagnosticCodes.TYPE_CONDITION, "bool", typeName(this.currType), expressionName(obj.getCondition()),
                    obj.getCondition().getLineNum(), obj.getCondition().getSpan(), "for condition", null);
        HashSet<String> before = new HashSet<>(this.currMethodLocalVar);
        loopDepth++;
        this.currMethodLocalVar = new HashSet<>(before);
        this.visit(obj.getBody());
        if (obj.getUpdate() != null) {
            this.visit(obj.getUpdate());
        }
        loopDepth--;
        this.currMethodLocalVar = before;
    }

    @Override
    public void visit(Ast.Stmt.Break obj) {
        if (loopDepth <= 0)
            error(obj.getLineNum(), "break statement must be inside a loop");
    }

    @Override
    public void visit(Ast.Stmt.Continue obj) {
        if (loopDepth <= 0)
            error(obj.getLineNum(), "continue statement must be inside a loop");
    }

    @Override
    public void visit(Ast.Stmt.Call obj) {
        validateImportBinding(obj.getName(), obj.getLineNum(), obj.getSpan());
        Ast.Type.T returnType = validateMethodCall(obj.getName(), obj.getInputParams(), obj.getLineNum(), obj.getSpan());
        obj.setReturnType(returnType);
        this.currType = returnType;
    }

    private static class FlowResult {
        final boolean canCompleteNormally;
        final boolean mustReturn;

        FlowResult(boolean canCompleteNormally, boolean mustReturn) {
            this.canCompleteNormally = canCompleteNormally;
            this.mustReturn = mustReturn;
        }
    }

    private boolean statementsMustReturn(ArrayList<Ast.Stmt.T> statements) {
        return flowOfStatements(statements).mustReturn;
    }

    private FlowResult flowOfStatements(ArrayList<Ast.Stmt.T> statements) {
        if (statements == null || statements.isEmpty()) {
            return new FlowResult(true, false);
        }
        boolean canCompleteNormally = true;
        for (Ast.Stmt.T statement : statements) {
            if (!canCompleteNormally) {
                break;
            }
            FlowResult result = flowOfStatement(statement);
            canCompleteNormally = result.canCompleteNormally;
            if (result.mustReturn) {
                return new FlowResult(false, true);
            }
        }
        return new FlowResult(canCompleteNormally, false);
    }

    private FlowResult flowOfStatement(Ast.Stmt.T statement) {
        if (statement instanceof Ast.Stmt.Return) {
            return new FlowResult(false, true);
        }
        if (statement instanceof Ast.Stmt.Block) {
            return flowOfStatements(((Ast.Stmt.Block) statement).getStmts());
        }
        if (statement instanceof Ast.Stmt.If) {
            Ast.Stmt.If ifStmt = (Ast.Stmt.If) statement;
            if (ifStmt.getElseStmt() == null) {
                return new FlowResult(true, false);
            }
            FlowResult thenFlow = flowOfStatement(ifStmt.getThenStmt());
            FlowResult elseFlow = flowOfStatement(ifStmt.getElseStmt());
            return new FlowResult(
                    thenFlow.canCompleteNormally || elseFlow.canCompleteNormally,
                    thenFlow.mustReturn && elseFlow.mustReturn);
        }
        return new FlowResult(true, false);
    }

    private ArrayList<Character> printfPlaceholders(String format, int lineNum) {
        ArrayList<Character> placeholders = new ArrayList<>();
        for (int i = 0; i < format.length(); i++) {
            if (format.charAt(i) != '%') {
                continue;
            }
            if (i + 1 >= format.length()) {
                error(lineNum, "format string contains % without a placeholder");
            }
            char placeholder = format.charAt(++i);
            if (placeholder == 'd' || placeholder == 'f') {
                placeholders.add(placeholder);
            } else {
                error(lineNum, "printf does not support placeholder %" + placeholder);
            }
        }
        return placeholders;
    }

    private void error(int lineNum, String msg){
        this.pass = false;
        Diagnostic diagnostic = diagnosticEngine.error(DiagnosticCodes.SEM_GENERAL)
                .message(msg)
                .primary(site.ilemon.util.SourceSpan.singlePoint(null, 0, Math.max(1, lineNum), 1), "here")
                .report();
        if (this.collectErrors) {
            this.errors.add(diagnostic.message());
            this.errorLineNumbers.add(lineNum);
            return;
        }
        throw new SemanticException(diagnostic);
    }

    private void internalError(int lineNum, String message) {
        this.pass = false;
        Diagnostic diagnostic = diagnosticEngine.error(DiagnosticCodes.INTERNAL_COMPILER_ERROR)
                .message(message)
                .primary(site.ilemon.util.SourceSpan.singlePoint(null, 0, Math.max(1, lineNum), 1), "internal compiler error")
                .report();
        if (this.collectErrors) {
            this.errors.add(diagnostic.message());
            this.errorLineNumbers.add(lineNum);
            return;
        }
        throw new SemanticException(diagnostic);
    }

    private void typeError(String code, String expected, String actual, String expression,
                           int lineNum, site.ilemon.util.SourceSpan span, String context, String note) {
        this.pass = false;
        var builder = diagnosticEngine.error(code)
                .message("type mismatch: expected " + expected + ", but found " + actual)
                .type(expected, actual, expression, context)
                .primary(span == null
                        ? site.ilemon.util.SourceSpan.singlePoint(null, 0, Math.max(1, lineNum), 1)
                        : span, context);
        if (note != null) {
            builder.note(note);
        }
        Diagnostic diagnostic = builder.report();
        if (this.collectErrors) {
            this.errors.add(diagnostic.message());
            this.errorLineNumbers.add(lineNum);
            return;
        }
        throw new SemanticException(diagnostic);
    }

    private String expressionName(Ast.Expr.T expression) {
        return expression == null ? "expression" : expression.getClass().getSimpleName();
    }

    /** Returns a spelling suggestion only when the candidate is unambiguously close. */
    private String nearestName(String unknown, java.util.Set<String> candidates) {
        if (unknown == null || unknown.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tied = false;
        for (String candidate : candidates) {
            if (candidate == null || candidate.equals(unknown)) {
                continue;
            }
            int distance = editDistance(unknown, candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
                tied = false;
            } else if (distance == bestDistance) {
                tied = true;
            }
        }
        int threshold = Math.max(1, unknown.length() / 3);
        return best != null && !tied && bestDistance <= threshold ? best : null;
    }

    private int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private void semanticError(String code, String message, int lineNum,
                               site.ilemon.util.SourceSpan span, String primaryLabel,
                               String note, String suggestion) {
        this.pass = false;
        var builder = diagnosticEngine.error(code)
                .message(message)
                .primary(span == null
                        ? site.ilemon.util.SourceSpan.singlePoint(null, 0, Math.max(1, lineNum), 1)
                        : span, primaryLabel);
        if (note != null) {
            builder.note(note);
        }
        if (suggestion != null && span != null) {
            builder.suggestion(span, suggestion);
        }
        Diagnostic diagnostic = builder.report();
        if (this.collectErrors) {
            this.errors.add(diagnostic.message());
            this.errorLineNumbers.add(lineNum);
            return;
        }
        throw new SemanticException(diagnostic);
    }

    private boolean isMatch(Ast.Type.T target,Ast.Type.T curr){
        if( target == null || curr == null )
            return false;
        if(target.getKind() == curr.getKind())
            return true;
        // Allow float to implicitly widen to double
        if(target.getKind() == TypeKind.DOUBLE && curr.getKind() == TypeKind.FLOAT)
            return true;
        if(target.getKind() == TypeKind.FLOAT && curr.getKind() == TypeKind.INT)
            return true;
        if(target.getKind() == TypeKind.DOUBLE && curr.getKind() == TypeKind.INT)
            return true;
        if(target.getKind() == TypeKind.INT && (curr.getKind() == TypeKind.CHAR || curr.getKind() == TypeKind.BYTE || curr.getKind() == TypeKind.SHORT))
            return true;
        if(target.getKind() == TypeKind.FLOAT && (curr.getKind() == TypeKind.BYTE || curr.getKind() == TypeKind.SHORT))
            return true;
        if(target.getKind() == TypeKind.FLOAT && curr.getKind() == TypeKind.CHAR)
            return true;
        if(target.getKind() == TypeKind.DOUBLE && (curr.getKind() == TypeKind.BYTE || curr.getKind() == TypeKind.SHORT))
            return true;
        if(target.getKind() == TypeKind.DOUBLE && curr.getKind() == TypeKind.CHAR)
            return true;
        if(target.getKind() == TypeKind.LONG
                && (curr.getKind() == TypeKind.INT || curr.getKind() == TypeKind.CHAR || curr.getKind() == TypeKind.BYTE || curr.getKind() == TypeKind.SHORT))
            return true;
        if(target.getKind() == TypeKind.FLOAT && curr.getKind() == TypeKind.LONG)
            return true;
        if(target.getKind() == TypeKind.DOUBLE && curr.getKind() == TypeKind.LONG)
            return true;
        return false;
    }

    private boolean isAssignable(Ast.Type.T target, Ast.Type.T actual, Ast.Expr.T expression) {
        if (target != null && target.getKind() == TypeKind.BYTE
                && actual != null && actual.getKind() == TypeKind.INT) {
            Long value = byteLiteralValue(expression);
            return value != null && value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
        }
        if (target != null && target.getKind() == TypeKind.SHORT
                && actual != null && actual.getKind() == TypeKind.INT) {
            Long value = integralLiteralValue(expression);
            return value != null && value >= java.lang.Short.MIN_VALUE && value <= java.lang.Short.MAX_VALUE;
        }
        return isMatch(target, actual);
    }

    private boolean rangeErrorIfNeeded(Ast.Type.T target, Ast.Type.T actual, Ast.Expr.T expression,
                                        int lineNum, site.ilemon.util.SourceSpan span, String context) {
        if (target == null || actual == null || actual.getKind() != TypeKind.INT) {
            return false;
        }
        Long value = integralLiteralValue(expression);
        if (target.getKind() == TypeKind.BYTE && value != null && (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE)) {
            site.ilemon.util.SourceSpan primarySpan = span != null ? span : expression.getSpan();
            semanticError(DiagnosticCodes.TYPE_BYTE_RANGE,
                    "byte literal is out of range: expected -128..127, but found " + value,
                    lineNum, primarySpan, context, "byte is a signed 8-bit type",
                    "use a value between -128 and 127");
            return true;
        }
        if (target.getKind() == TypeKind.SHORT && value != null
                && (value < java.lang.Short.MIN_VALUE || value > java.lang.Short.MAX_VALUE)) {
            site.ilemon.util.SourceSpan primarySpan = span != null ? span : expression.getSpan();
            semanticError(DiagnosticCodes.TYPE_SHORT_RANGE,
                    "short literal is out of range: expected -32768..32767, but found " + value,
                    lineNum, primarySpan, context, "short is a signed 16-bit type",
                    "use a value between -32768 and 32767");
            return true;
        }
        return false;
    }

    private Long byteLiteralValue(Ast.Expr.T expression) {
        return integralLiteralValue(expression);
    }

    private boolean shortRangeErrorIfNeeded(Ast.Type.T target, Ast.Type.T actual, Ast.Expr.T expression,
                                            int lineNum, site.ilemon.util.SourceSpan span, String context) {
        if (target == null || target.getKind() != TypeKind.SHORT
                || actual == null || actual.getKind() != TypeKind.INT) {
            return false;
        }
        Long value = integralLiteralValue(expression);
        if (value != null && (value < java.lang.Short.MIN_VALUE || value > java.lang.Short.MAX_VALUE)) {
            site.ilemon.util.SourceSpan primarySpan = span != null ? span : expression.getSpan();
            semanticError(DiagnosticCodes.TYPE_SHORT_RANGE,
                    "short literal is out of range: expected -32768..32767, but found " + value,
                    lineNum, primarySpan, context, "short is a signed 16-bit type",
                    "use a value between -32768 and 32767");
            return true;
        }
        return false;
    }

    private Long integralLiteralValue(Ast.Expr.T expression) {
        if (expression instanceof Ast.Expr.Number number
                && number.getType().getKind() == TypeKind.INT) {
            try {
                return Long.parseLong(number.getValue().toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expression instanceof Ast.Expr.Sub sub
                && sub.getLeft() instanceof Ast.Expr.Number zero
                && sub.getRight() instanceof Ast.Expr.Number number
                && zero.getValue().toString().equals("0")) {
            try {
                return -Long.parseLong(number.getValue().toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Ast.Type.T promoteNumeric(Ast.Type.T left, Ast.Type.T right) {
        return TypeRules.promotedNumericType(left, right);
    }

    private boolean isNumberType(Ast.Type.T type) {
        return TypeRules.isNumeric(type);
    }

    private boolean isIntegerLike(Ast.Type.T type) {
        return TypeRules.isIntegerLike(type);
    }

    private boolean isArrayType(Ast.Type.T type) {
        if (type == null) {
            return false;
        }
        TypeKind kind = type.getKind();
        return kind == TypeKind.INT_ARRAY || kind == TypeKind.FLOAT_ARRAY
                || kind == TypeKind.DOUBLE_ARRAY || kind == TypeKind.BOOL_ARRAY
                || kind == TypeKind.STRING_ARRAY || kind == TypeKind.BYTE_ARRAY
                || kind == TypeKind.SHORT_ARRAY || kind == TypeKind.CHAR_ARRAY || kind == TypeKind.LONG_ARRAY;
    }

    /**
     * Validate equality operators (== / !=): types must match.
     */
    private void checkComparison(Ast.Expr.T left, Ast.Expr.T right, String op, int lineNum) {
        this.visit(left);
        Ast.Type.T leftType = this.currType;
        this.visit(right);
        if (isArrayType(leftType) || isArrayType(this.currType)) {
            error(lineNum, String.format("comparison operator '%s' does not support array operands: left is %s, right is %s",
                    op, typeName(leftType), typeName(this.currType)));
        }
        if (promoteNumeric(leftType, this.currType) == null && !isMatch(leftType, this.currType)) {
            typeError(DiagnosticCodes.TYPE_OPERATOR, typeName(leftType), typeName(this.currType), "comparison expression",
                    lineNum, left.getSpan(), "comparison operator '" + op + "'", null);
        }
        this.currType = new Ast.Type.Bool();
    }

    /**
     * Validate ordering comparison operators (> / < / >= / <=): requires matching numeric types.
     */
    private void checkOrderComparison(Ast.Expr.T left, Ast.Expr.T right, String op, int lineNum) {
        this.visit(left);
        Ast.Type.T leftType = this.currType;
        this.visit(right);
        if (promoteNumeric(leftType, this.currType) == null) {
            typeError(DiagnosticCodes.TYPE_OPERATOR, "numeric operands", typeName(leftType) + " and " + typeName(this.currType),
                    "comparison expression", lineNum, left.getSpan(), "comparison operator '" + op + "'", null);
        }
        this.currType = new Ast.Type.Bool();
    }

    /**
     * Shared method call validation logic for Expr.Call and Stmt.Call.
     * Validates whether method exists, argument count matches, and argument types match.
     * @return Method return type
     */
    private Ast.Type.T validateMethodCall(String methodName, ArrayList<Ast.Expr.T> inputParams,
                                          int lineNum, site.ilemon.util.SourceSpan span) {
        Ast.Method.MethodSingle method = this.methodMap.get(methodName);
        if (method == null) {
            semanticError(DiagnosticCodes.SEM_UNKNOWN_FUNCTION, "undefined function: " + methodName,
                    lineNum, span, "unknown function",
                    "no function with this name is declared in the current program",
                    nearestName(methodName, methodMap.keySet()));
            return unknownType();
        }
        if (inputParams.size() != method.getFormals().size()) {
            error(lineNum, String.format("method '%s' has an incorrect argument count: expected %d, but found %d",
                    methodName, method.getFormals().size(), inputParams.size()));
        }
        for (int i = 0; i < inputParams.size(); i++) {
            this.visit(inputParams.get(i));
            Ast.Type.T actualType = this.currType;
            this.visit(method.getFormals().get(i));
            Ast.Type.T expectedType = this.currType;
            if (!isAssignable(expectedType, actualType, inputParams.get(i))) {
                Ast.Expr.T argument = inputParams.get(i);
                if (!rangeErrorIfNeeded(expectedType, actualType, argument, argument.getLineNum(), argument.getSpan(),
                        "argument " + (i + 1) + " of '" + methodName + "'")) {
                    typeError(DiagnosticCodes.TYPE_ARGUMENT, typeName(expectedType), typeName(actualType), expressionName(argument),
                            argument.getLineNum(), argument.getSpan(), "argument " + (i + 1) + " of '" + methodName + "'", null);
                }
            }
        }
        return this.methodNameRetTypeMap.get(methodName);
    }

    // ========== Array-related visit methods ==========

    @Override
    public void visit(Ast.Type.IntArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.ByteArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.ShortArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.CharArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.LongArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.FloatArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.DoubleArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.BoolArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.StringArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Expr.ArrayAccess obj) {
        // Check whether the array has been declared
        MethodVarTable mTable = this.methodVarTable.get(currMethodName);
        if (mTable == null) {
            internalError(obj.getLineNum(), "internal error: variable table for method '" + currMethodName + "' was not found");
        }
        if (mTable == null) {
            this.currType = unknownType();
            return;
        }
        Ast.Type.T arrayType = mTable.get(obj.getArrayName());
        if (arrayType == null) {
            semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array: " + obj.getArrayName(),
                    obj.getLineNum(), obj.getSpan(), "unknown array",
                    "the name is not declared in the current method scope", null);
        }
        if (arrayType == null) {
            this.currType = unknownType();
            return;
        }
        Ast.Type.T elementType = getElementType(arrayType);
        if (elementType == null) {
            error(obj.getLineNum(), String.format("variable '%s' is not an array; actual type is %s",
                    obj.getArrayName(), typeName(arrayType)));
        }
        // Index type must be int
        if (elementType == null) {
            this.currType = unknownType();
            return;
        }
        this.visit(obj.getIndex());
        if (this.currType.getKind() != TypeKind.INT) {
            typeError(DiagnosticCodes.TYPE_INDEX, "int", typeName(this.currType), expressionName(obj.getIndex()),
                    obj.getIndex().getLineNum(), obj.getIndex().getSpan(), "array index", null);
        }
        // Set element type
        obj.setElementType(elementType);
        this.currType = obj.getElementType();
    }

    @Override
    public void visit(Ast.Expr.ArrayLength obj) {
        MethodVarTable mTable = this.methodVarTable.get(currMethodName);
        if (mTable == null) {
            internalError(obj.getLineNum(), "internal error: variable table for method '" + currMethodName + "' was not found");
            this.currType = unknownType();
            return;
        }
        Ast.Type.T arrayType = mTable.get(obj.getArrayName());
        if (arrayType == null) {
            semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array: " + obj.getArrayName(),
                    obj.getLineNum(), obj.getSpan(), "unknown array",
                    "the name is not declared in the current method scope", null);
        }
        if (arrayType == null) {
            this.currType = unknownType();
            return;
        }
        if (getElementType(arrayType) == null) {
            error(obj.getLineNum(), String.format("variable '%s' is not an array; actual type is %s",
                    obj.getArrayName(), typeName(arrayType)));
            this.currType = unknownType();
            return;
        }
        this.currType = new Ast.Type.Int();
    }

    @Override
    public void visit(Ast.Stmt.ArrayAssign obj) {
        // Check whether the array has been declared
        MethodVarTable mTable = this.methodVarTable.get(currMethodName);
        if (mTable == null) {
            internalError(obj.getLineNum(), "internal error: variable table for method '" + currMethodName + "' was not found");
            this.currType = unknownType();
            return;
        }
        Ast.Type.T arrayType = mTable.get(obj.getArrayName());
        if (arrayType == null) {
            semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array: " + obj.getArrayName(),
                    obj.getLineNum(), obj.getSpan(), "unknown array",
                    "the name is not declared in the current method scope", null);
            this.currType = unknownType();
            return;
        }
        // Set element type
        Ast.Type.T elementType = getElementType(arrayType);
        if (elementType == null) {
            error(obj.getLineNum(), String.format("variable '%s' is not an array; actual type is %s",
                    obj.getArrayName(), typeName(arrayType)));
            this.currType = unknownType();
            return;
        }
        obj.setElementType(elementType);
        // Check index type
        this.visit(obj.getIndex());
        if (this.currType.getKind() != TypeKind.INT) {
            typeError(DiagnosticCodes.TYPE_INDEX, "int", typeName(this.currType), expressionName(obj.getIndex()),
                    obj.getIndex().getLineNum(), obj.getIndex().getSpan(), "array index", null);
        }
        // Check assignment type
        this.visit(obj.getExpr());
        if (!isAssignable(elementType, this.currType, obj.getExpr())) {
            if (!rangeErrorIfNeeded(elementType, this.currType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                    "array element assignment")) {
                if (!shortRangeErrorIfNeeded(elementType, this.currType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                        "array element assignment")) {
                    typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(elementType), typeName(this.currType),
                            expressionName(obj.getExpr()), obj.getLineNum(), obj.getSpan(), "array element assignment", null);
                }
            }
        }
    }

    // Get array element type
    private Ast.Type.T getElementType(Ast.Type.T arrayType) {
        if (arrayType instanceof Ast.Type.IntArray) {
            return new Ast.Type.Int();
        } else if (arrayType instanceof Ast.Type.ByteArray) {
            return new Ast.Type.Byte();
        } else if (arrayType instanceof Ast.Type.ShortArray) {
            return new Ast.Type.Short();
        } else if (arrayType instanceof Ast.Type.CharArray) {
            return new Ast.Type.Char();
        } else if (arrayType instanceof Ast.Type.LongArray) {
            return new Ast.Type.Long();
        } else if (arrayType instanceof Ast.Type.FloatArray) {
            return new Ast.Type.Float();
        } else if (arrayType instanceof Ast.Type.DoubleArray) {
            return new Ast.Type.Double();
        } else if (arrayType instanceof Ast.Type.BoolArray) {
            return new Ast.Type.Bool();
        } else if (arrayType instanceof Ast.Type.StringArray) {
            return new Ast.Type.Str();
        }
        return null;
    }
}
