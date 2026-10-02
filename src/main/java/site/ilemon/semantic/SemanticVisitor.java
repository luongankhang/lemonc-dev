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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Set while visiting the right-hand side of a field-assignment statement,
     *  allowing enum↔int compatibility only in those contexts. */
    private boolean allowEnumIntAssignment = false;

    private HashSet<String> currMethodLocalVar;

    /** Variables assigned in every switch case (to resolve false positives). */
    private Set<String> assignedInAllCases;

    /**
     * Pointer-typed locals of the current method whose value may reference a
     * local of this very method (seeded by {@code p = &x}). Returning such a
     * value would hand out a pointer to storage that is about to die.
     */
    private final java.util.Set<String> localAddrTaint = new java.util.HashSet<>();

    /** Formal parameters of the current method (address-of is rejected on them). */
    private final java.util.Set<String> currMethodFormals = new java.util.HashSet<>();

    private int loopDepth = 0;

    /** Nesting depth of switch statements; break is legal inside loop or switch. */
    private int switchDepth = 0;

    private HashMap<String,Ast.Method.MethodSingle> methodMap;

    /** Global constants visible in the current program (incl. re-exported {@code alias_NAME} copies). */
    private HashMap<String, Ast.ConstDecl> globalConsts = new HashMap<>();
    /** Identity guard so a shared imported-module constant is validated once. */
    private java.util.Set<Ast.ConstDecl> validatedConsts = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private Ast.Type.T typeOfMethodDeclared;

    private HashSet<String> importedModuleNames = new HashSet<>();
    private ScopeManager scopeManager = new ScopeManager();

    /** Declared structs of the current program, keyed by struct name. */
    private final HashMap<String, Ast.StructDecl> structTable = new HashMap<>();
    /** Declared enums of the current program, keyed by enum name. */
    private final HashMap<String, Ast.EnumDecl> enumTable = new HashMap<>();
    private final HashMap<String, EnumMemberInfo> enumMemberTable = new HashMap<>();

    public record EnumMemberInfo(String enumName, String memberName, int value, Ast.EnumDecl decl) {}

    private Ast.MainClass.MainClassSingle currentMainClass;
    private String currDeclaringModule;

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
        if (rejectPointerArithmetic(obj.getLineNum(), "+", leftType, this.currType, obj.getSpan())) return;
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
        if (!isLocalTarget && enumMemberTable.containsKey(obj.getId().getId())) {
            semanticError(DiagnosticCodes.SEM_CONST_IMMUTABLE,
                    "cannot assign to enum member '" + obj.getId().getId() + "': enum members are immutable",
                    obj.getLineNum(), obj.getSpan(), "immutable enum member",
                    "enum members cannot be reassigned", null);
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
            // Pointer alias tracking: a pointer local that now (or previously)
            // held the address of this frame's own storage must never leave the
            // frame through a return. The taint is monotonic and conservative.
            if (isPointerType(targetType) && exprMayPointToLocal(obj.getExpr())) {
                this.localAddrTaint.add(obj.getId().getId());
            }
            if (isArrayType(this.currType) || isArrayType(exprType)) {
                if (!(isArrayType(this.currType) && exprType != null && exprType.getKind() == TypeKind.NULL)) {
                    typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(targetType), typeName(exprType),
                            expressionName(obj.getExpr()), obj.getLineNum(), obj.getSpan(),
                            "array assignment", "arrays cannot be assigned as whole values");
                    return;
                }
            }
            // Struct values copy field-by-field (by-value semantics); pointer
            // alias taint from exprMayPointToLocal is irrelevant for structs.
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
        MethodVarTable mTable = this.methodVarTable.get(currMethodName);
        if (mTable != null) mTable.enterScope();
        for( Ast.Stmt.T stmt : obj.getStmts()){
            this.visit(stmt);
        }
        if (mTable != null) {
            Set<String> leavingVars = mTable.currentScopeNames();
            this.currMethodLocalVar.removeAll(leavingVars);
            mTable.exitScope(leavingVars);
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
        if (rejectPointerArithmetic(obj.getLineNum(), "/", leftType, this.currType, obj.getSpan())) return;
        checkSameOperandTypes(obj.getLineNum(), "/", leftType, this.currType);
    }

    @Override
    public void visit(Ast.Expr.Mod obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        if (rejectPointerArithmetic(obj.getLineNum(), "%", leftType, this.currType, obj.getSpan())) return;
        Ast.Type.T rightType = this.currType;
        if (leftType == null || rightType == null
                || !isIntegerLike(leftType) || !isIntegerLike(rightType)) {
            typeError(DiagnosticCodes.TYPE_OPERATOR, "int or byte", typeName(leftType) + " and " + typeName(rightType),
                    expressionName(obj), obj.getLineNum(), obj.getSpan(), "operator '%'", "the remainder operator requires int operands");
        }
        this.currType = (leftType != null && rightType != null
                && (leftType.getKind() == TypeKind.LONG || rightType.getKind() == TypeKind.LONG))
                ? new Ast.Type.Long()
                : new Ast.Type.Int();
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
            EnumMemberInfo enumMember = enumMemberTable.get(obj.getId());
            if (enumMember != null) {
                if (enumMember.decl().getVisibility() == Ast.Visibility.PRIVATE) {
                    if (enumMember.decl().getDeclaringModule() != null
                            && !java.util.Objects.equals(this.currDeclaringModule, enumMember.decl().getDeclaringModule())) {
                        semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                                "cannot access member '" + enumMember.memberName() + "' of private enum '" + enumMember.enumName() + "'",
                                obj.getLineNum(), obj.getSpan(), "private enum member",
                                "enum is private to its module", null);
                        this.currType = unknownType();
                        obj.setType(this.currType);
                        return;
                    }
                }
                Ast.Type.Enum enumType = new Ast.Type.Enum(enumMember.enumName());
                obj.setType(enumType);
                this.currType = enumType;
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
                    "constant '" + constant.getId() + "' must have a scalar type (or struct type)",
                    constant.getLineNum(), constant.getSpan(), "invalid constant type",
                    "constants cannot be arrays or void", null);
            return;
        }
        if (type.getKind() == TypeKind.STRUCT) {
            validateStructTypeReference(type, constant.getLineNum(), constant.getSpan(), "constant type");
        }
        if (type.getKind() == TypeKind.ENUM) {
            validateEnumTypeReference(type, constant.getLineNum(), constant.getSpan(), "constant type");
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
        if (initializer instanceof Ast.Expr.InitializerList initList) {
            for (Ast.Expr.T elem : initList.getElements()) {
                if (!isLiteralInitializer(elem)) {
                    return false;
                }
            }
            return true;
        }
        if (initializer instanceof Ast.Expr.Sub sub
                && sub.getLeft() instanceof Ast.Expr.Number zero
                && "0".equals(String.valueOf(zero.getValue()))) {
            return sub.getRight() instanceof Ast.Expr.Number;
        }
        if (initializer instanceof Ast.Expr.UnaryMinus um) {
            return um.getExp() instanceof Ast.Expr.Number;
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
        if (initializer instanceof Ast.Expr.InitializerList initList) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < initList.getElements().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(resolveLiteralValue(initList.getElements().get(i)));
            }
            sb.append("}");
            return sb.toString();
        }
        if (initializer instanceof Ast.Expr.Sub sub) {
            String right = resolveLiteralValue(sub.getRight());
            return right == null ? null : "-" + right;
        }
        if (initializer instanceof Ast.Expr.UnaryMinus um) {
            String op = resolveLiteralValue(um.getExp());
            return op == null ? null : "-" + op;
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
        this.currentMainClass = mainClassSingle;
        this.currDeclaringModule = null;
        for (Ast.Method.T m : mainClassSingle.getMethods()) {
            if (m instanceof Ast.Method.MethodSingle ms && ms.getDeclaringModule() != null) {
                this.currDeclaringModule = ms.getDeclaringModule();
                break;
            }
        }
        if (this.currDeclaringModule == null) {
            for (Ast.StructDecl s : mainClassSingle.getStructs()) {
                if (s.getDeclaringModule() != null) {
                    this.currDeclaringModule = s.getDeclaringModule();
                    break;
                }
            }
        }
        if (this.currDeclaringModule == null) {
            for (Ast.EnumDecl e : mainClassSingle.getEnums()) {
                if (e.getDeclaringModule() != null) {
                    this.currDeclaringModule = e.getDeclaringModule();
                    break;
                }
            }
        }
        scopeManager = new ScopeManager();
        importedModuleNames.clear();
        structTable.clear();
        enumTable.clear();
        enumMemberTable.clear();
        for (Ast.EnumDecl enumDecl : mainClassSingle.getEnums()) {
            visitEnumDecl(enumDecl);
        }
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
        // Struct declarations: register, check duplicates, and validate fields.
        // Done before constants and method bodies so constants and method signatures can reference structs.
        for (Ast.StructDecl structDecl : mainClassSingle.getStructs()) {
            visitStructDecl(structDecl);
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

    // ==================================================== struct declarations

    /** Registers and validates one struct declaration. */
    private void visitStructDecl(Ast.StructDecl structDecl) {
        if (structDecl == null) {
            return;
        }
        if (structTable.containsKey(structDecl.getName())) {
            semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION,
                    "duplicate struct declaration: " + structDecl.getName(),
                    structDecl.getLineNum(), structDecl.getSpan(), "duplicate struct",
                    "the struct was declared earlier", null);
            return;
        }
        structTable.put(structDecl.getName(), structDecl);
        if (structDecl.getFields() == null || structDecl.getFields().isEmpty()) {
            error(structDecl.getLineNum(), "struct '" + structDecl.getName() + "' must declare at least one field");
            return;
        }
        String prevModule = this.currDeclaringModule;
        this.currDeclaringModule = structDecl.getDeclaringModule();
        try {
            java.util.Set<String> fieldNames = new java.util.HashSet<>();
            for (Ast.Declare.T field : structDecl.getFields()) {
                if (!(field instanceof Ast.Declare.DeclareSingle single)) {
                    continue;
                }
                if (single.getId() == null || !fieldNames.add(single.getId())) {
                    error(structDecl.getLineNum(), "duplicate field declaration in struct '"
                            + structDecl.getName() + "': " + single.getId());
                    continue;
                }
                validateStructFieldType(structDecl, single);
            }
        } finally {
            this.currDeclaringModule = prevModule;
        }
    }

    /** A struct field may be a scalar, bool, array, string, another struct, or pointer Ã¢â‚¬â€ never void. */
    private void validateStructFieldType(Ast.StructDecl owner, Ast.Declare.DeclareSingle field) {
        Ast.Type.T type = field.getType();
        if (type == null) {
            error(field.getLineNum(), "field '" + field.getId() + "' has no type");
            return;
        }
        if (type.getKind() == TypeKind.VOID) {
            semanticError(DiagnosticCodes.SEM_GENERAL,
                    "invalid field type for '" + owner.getName() + "." + field.getId() + "': " + typeName(type),
                    field.getLineNum(), field.getSpan(), "unsupported field type",
                    "struct fields cannot be void", null);
            return;
        }
        if (type.getKind() == TypeKind.STRUCT
                && ((Ast.Type.Struct) type).getSimpleName().equals(owner.getName())) {
            error(field.getLineNum(), "struct '" + owner.getName() + "' cannot contain itself as a value field");
            return;
        }
        validateStructTypeReference(type, field.getLineNum(), field.getSpan(), "field type");
        validateEnumTypeReference(type, field.getLineNum(), field.getSpan(), "field type");
        if (owner.getVisibility() == Ast.Visibility.PUBLIC && field.getVisibility() == Ast.Visibility.PUBLIC) {
            if (referencesPrivateStruct(type)) {
                semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                        "public struct '" + owner.getName() + "' cannot expose private struct in public field '" + field.getId() + "'",
                        field.getLineNum(), field.getSpan(), "private struct in public field",
                        "declare struct with 'pub' or make field private", null);
            }
            if (referencesPrivateEnum(type)) {
                semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                        "public struct '" + owner.getName() + "' cannot expose private enum in public field '" + field.getId() + "'",
                        field.getLineNum(), field.getSpan(), "private enum in public field",
                        "declare enum with 'pub' or make field private", null);
            }
        }
        if (isPointerType(type) && !isLegalStructPointee(((Ast.Type.Pointer) type).getPointee())) {
            semanticError(DiagnosticCodes.TYPE_POINTER_ASSIGNMENT,
                    "unsupported pointer field type '" + typeName(type) + "' in struct '" + owner.getName() + "'",
                    field.getLineNum(), field.getSpan(), "unsupported pointer type",
                    "struct pointer fields must point to scalars, bool, or structs", null);
        }
    }

    /** Pointees allowed inside struct pointer fields. */
    private boolean isLegalStructPointee(Ast.Type.T pointee) {
        if (pointee == null) {
            return false;
        }
        if (isPointerType(pointee)) {
            return isLegalStructPointee(((Ast.Type.Pointer) pointee).getPointee());
        }
        if (pointee.getKind() == TypeKind.STRUCT) {
            return resolveStruct(((Ast.Type.Struct) pointee).getName()) != null;
        }
        if (pointee.getKind() == TypeKind.ENUM) {
            return resolveEnum(((Ast.Type.Enum) pointee).getName()) != null;
        }
        return switch (pointee.getKind()) {
            case BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, BOOL, STRING, ENUM -> true;
            default -> isArrayType(pointee);
        };
    }

    /** Resolves a struct name to its declaration, or null when unknown. */
    private Ast.StructDecl resolveStruct(String name) {
        if (name == null) return null;
        Ast.StructDecl decl = structTable.get(name);
        if (decl != null) return decl;
        int dot = name.indexOf('.');
        if (dot >= 0) {
            String alias = name.substring(0, dot);
            String simpleName = name.substring(dot + 1);
            if (currentMainClass != null && currentMainClass.getModuleStructs().containsKey(alias)) {
                for (Ast.StructDecl s : currentMainClass.getModuleStructs().get(alias)) {
                    if (s.getName().equals(simpleName)) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    private boolean referencesPrivateStruct(Ast.Type.T type) {
        if (type == null) return false;
        if (isPointerType(type)) return referencesPrivateStruct(((Ast.Type.Pointer) type).getPointee());
        if (type.getKind() != TypeKind.STRUCT) return false;
        Ast.StructDecl decl = resolveStruct(((Ast.Type.Struct) type).getName());
        return decl != null && decl.getVisibility() == Ast.Visibility.PRIVATE;
    }

    /**
     * Checks whether an alias is known to the compiler — either as a top-level import
     * or as a nested module that was merged during import propagation.
     */
    private boolean isKnownModuleAlias(String alias) {
        return importedModuleNames.contains(alias)
                || scopeManager.resolveImport(alias) != null
                || (currentMainClass != null
                        && (currentMainClass.getModuleStructs().containsKey(alias)
                                || currentMainClass.getModuleEnums().containsKey(alias)));
    }

    private void validateStructTypeReference(Ast.Type.T type, int line, site.ilemon.util.SourceSpan span, String context) {
        if (type == null) return;
        if (isPointerType(type)) {
            validateStructTypeReference(((Ast.Type.Pointer) type).getPointee(), line, span, context);
            return;
        }
        if (type.getKind() != TypeKind.STRUCT) {
            return;
        }
        Ast.Type.Struct structType = (Ast.Type.Struct) type;
        String alias = structType.getModuleAlias();
        String simpleName = structType.getSimpleName();

        if (alias != null) {
            if (!isKnownModuleAlias(alias)) {
                semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                        "module import '" + alias + "' is not visible in this scope",
                        line, span, "import is out of scope", "declare the import in this lexical scope", null);
                return;
            }
            java.util.List<Ast.StructDecl> modStructs = currentMainClass != null ? currentMainClass.getModuleStructs().get(alias) : null;
            Ast.StructDecl targetStruct = null;
            if (modStructs != null) {
                for (Ast.StructDecl s : modStructs) {
                    if (s.getName().equals(simpleName)) {
                        targetStruct = s;
                        break;
                    }
                }
            }
            if (targetStruct == null) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                        "module import '" + alias + "' has no struct '" + simpleName + "'",
                        line, span, "unknown struct", "struct is not declared in module '" + alias + "'", null);
                return;
            }
            if (targetStruct.getVisibility() == Ast.Visibility.PRIVATE) {
                semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                        "cannot access private struct '" + simpleName + "' from module '" + alias + "'",
                        line, span, "private struct", "struct is private to module '" + alias + "'", null);
                return;
            }
        } else {
            Ast.StructDecl targetStruct = structTable.get(simpleName);
            if (targetStruct != null) {
                if (targetStruct.getVisibility() == Ast.Visibility.PRIVATE) {
                    if (targetStruct.getDeclaringModule() != null
                            && !java.util.Objects.equals(this.currDeclaringModule, targetStruct.getDeclaringModule())) {
                        semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                                "cannot access private struct '" + simpleName + "'",
                                line, span, "private struct", "struct is private to its module", null);
                    }
                }
                return;
            }
            if (currentMainClass != null) {
                for (Map.Entry<String, java.util.ArrayList<Ast.StructDecl>> entry : currentMainClass.getModuleStructs().entrySet()) {
                    for (Ast.StructDecl s : entry.getValue()) {
                        if (s.getName().equals(simpleName) && s.getVisibility() == Ast.Visibility.PRIVATE) {
                            if (s.getDeclaringModule() != null
                                    && java.util.Objects.equals(this.currDeclaringModule, s.getDeclaringModule())) {
                                return;
                            }
                            semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                                    "cannot access private struct '" + simpleName + "' from module '" + entry.getKey() + "'",
                                    line, span, "private struct", "struct is private to module '" + entry.getKey() + "'", null);
                            return;
                        }
                    }
                }
            }
            semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                    "unknown struct type: " + simpleName,
                    line, span, "unknown struct", "declare the struct before using it as a " + context, null);
        }
    }

    // ==================================================== enum declarations

    /** Registers and validates one enum declaration. */
    private void visitEnumDecl(Ast.EnumDecl enumDecl) {
        if (enumDecl == null) {
            return;
        }
        if (enumTable.containsKey(enumDecl.getName())) {
            semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION,
                    "duplicate enum declaration: " + enumDecl.getName(),
                    enumDecl.getLineNum(), enumDecl.getSpan(), "duplicate enum",
                    "the enum was declared earlier", null);
            return;
        }
        enumTable.put(enumDecl.getName(), enumDecl);
        if (enumDecl.getMembers() == null || enumDecl.getMembers().isEmpty()) {
            error(enumDecl.getLineNum(), "enum '" + enumDecl.getName() + "' must declare at least one member");
            return;
        }
        java.util.Set<String> memberNames = new java.util.HashSet<>();
        for (Ast.EnumMember member : enumDecl.getMembers()) {
            if (!memberNames.add(member.getName())) {
                semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION,
                        "duplicate member declaration in enum '" + enumDecl.getName() + "': " + member.getName(),
                        member.getLineNum(), member.getSpan(), "duplicate enum member",
                        "enum member name is already declared", null);
                continue;
            }
            EnumMemberInfo info = new EnumMemberInfo(enumDecl.getName(), member.getName(), member.getValue(), enumDecl);
            enumMemberTable.putIfAbsent(member.getName(), info);
            enumMemberTable.put(enumDecl.getName() + "." + member.getName(), info);
        }
    }

    /** Resolves an enum name to its declaration, or null when unknown. */
    private Ast.EnumDecl resolveEnum(String name) {
        if (name == null) return null;
        Ast.EnumDecl decl = enumTable.get(name);
        if (decl != null) return decl;
        int dot = name.indexOf('.');
        if (dot >= 0) {
            String alias = name.substring(0, dot);
            String simpleName = name.substring(dot + 1);
            if (currentMainClass != null && currentMainClass.getModuleEnums().containsKey(alias)) {
                for (Ast.EnumDecl e : currentMainClass.getModuleEnums().get(alias)) {
                    if (e.getName().equals(simpleName)) {
                        return e;
                    }
                }
            }
        }
        return null;
    }

    private boolean referencesPrivateEnum(Ast.Type.T type) {
        if (type == null) return false;
        if (isPointerType(type)) return referencesPrivateEnum(((Ast.Type.Pointer) type).getPointee());
        if (type.getKind() != TypeKind.ENUM) return false;
        Ast.EnumDecl decl = resolveEnum(((Ast.Type.Enum) type).getName());
        return decl != null && decl.getVisibility() == Ast.Visibility.PRIVATE;
    }

    private void validateEnumTypeReference(Ast.Type.T type, int line, site.ilemon.util.SourceSpan span, String context) {
        if (type == null) return;
        if (isPointerType(type)) {
            validateEnumTypeReference(((Ast.Type.Pointer) type).getPointee(), line, span, context);
            return;
        }
        if (type.getKind() != TypeKind.ENUM) {
            return;
        }
        Ast.Type.Enum enumType = (Ast.Type.Enum) type;
        String alias = enumType.getModuleAlias();
        String simpleName = enumType.getSimpleName();

        if (alias != null) {
            if (!isKnownModuleAlias(alias)) {
                semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                        "module import '" + alias + "' is not visible in this scope",
                        line, span, "import is out of scope", "declare the import in this lexical scope", null);
                return;
            }
            java.util.List<Ast.EnumDecl> modEnums = currentMainClass != null ? currentMainClass.getModuleEnums().get(alias) : null;
            Ast.EnumDecl targetEnum = null;
            if (modEnums != null) {
                for (Ast.EnumDecl e : modEnums) {
                    if (e.getName().equals(simpleName)) {
                        targetEnum = e;
                        break;
                    }
                }
            }
            if (targetEnum == null) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                        "module import '" + alias + "' has no enum '" + simpleName + "'",
                        line, span, "unknown enum", "enum is not declared in module '" + alias + "'", null);
                return;
            }
            if (targetEnum.getVisibility() == Ast.Visibility.PRIVATE) {
                semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                        "cannot access private enum '" + simpleName + "' from module '" + alias + "'",
                        line, span, "private enum", "enum is private to module '" + alias + "'", null);
                return;
            }
        } else {
            Ast.EnumDecl targetEnum = enumTable.get(simpleName);
            if (targetEnum != null) {
                if (targetEnum.getVisibility() == Ast.Visibility.PRIVATE) {
                    if (targetEnum.getDeclaringModule() != null
                            && !java.util.Objects.equals(this.currDeclaringModule, targetEnum.getDeclaringModule())) {
                        semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                                "cannot access private enum '" + simpleName + "'",
                                line, span, "private enum", "enum is private to its module", null);
                    }
                }
                return;
            }
            if (currentMainClass != null) {
                for (Map.Entry<String, java.util.ArrayList<Ast.EnumDecl>> entry : currentMainClass.getModuleEnums().entrySet()) {
                    for (Ast.EnumDecl e : entry.getValue()) {
                        if (e.getName().equals(simpleName) && e.getVisibility() == Ast.Visibility.PRIVATE) {
                            if (e.getDeclaringModule() != null
                                    && java.util.Objects.equals(this.currDeclaringModule, e.getDeclaringModule())) {
                                return;
                            }
                            semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                                    "cannot access private enum '" + simpleName + "' from module '" + entry.getKey() + "'",
                                    line, span, "private enum", "enum is private to module '" + entry.getKey() + "'", null);
                            return;
                        }
                    }
                }
            }
            semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                    "unknown enum type: " + simpleName,
                    line, span, "unknown enum", "declare the enum before using it as a " + context, null);
        }
    }

    /** True when the type mentions a struct that is not declared. */
    private boolean referencesUnknownStruct(Ast.Type.T type) {
        if (type == null) {
            return false;
        }
        if (type.getKind() == TypeKind.STRUCT) {
            return resolveStruct(((Ast.Type.Struct) type).getName()) == null;
        }
        if (isPointerType(type)) {
            return referencesUnknownStruct(((Ast.Type.Pointer) type).getPointee());
        }
        return false;
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
        this.localAddrTaint.clear();
        this.currMethodFormals.clear();
        if (obj.getFormals() != null) {
            for (Ast.Declare.T formal : obj.getFormals()) {
                if (formal instanceof Ast.Declare.DeclareSingle single) {
                    this.currMethodFormals.add(single.getId());
                    mTable.putFormal(single);
                }
            }
        }

        // Uninitialized declarations parsed at method entry (or legacy AST locals)
        // are registered in mTable up-front. Declarations that are VarDecl statements
        // will be registered when the statement is visited.
        // Use ID-based set (not IdentityHashMap) because ModuleLoader deep-copies
        // DeclareSingle objects, breaking identity equality.
        Set<String> varDeclIds = new HashSet<>();
        collectVarDeclNodeIds(obj.getStms(), varDeclIds);
        if (obj.getLocals() != null) {
            for (Ast.Declare.T dec : obj.getLocals()) {
                if (dec instanceof Ast.Declare.DeclareSingle declareSingle
                        && !varDeclIds.contains(declareSingle.getId())) {
                    if (!isArrayType(declareSingle.getType())) {
                        this.currMethodLocalVar.add(declareSingle.getId());
                    }
                    mTable.declare(declareSingle);
                }
            }
        }

        this.methodVarTable.put(obj.getId(),mTable);
        this.currMethodName = obj.getId();
        this.currDeclaringModule = obj.getDeclaringModule();
        this.typeOfMethodDeclared = obj.getRetType();

        validateStructTypeReference(obj.getRetType(), obj.getLineNum(), obj.getSpan(), "return type");
        validateEnumTypeReference(obj.getRetType(), obj.getLineNum(), obj.getSpan(), "return type");
        if (obj.getVisibility() == Ast.Visibility.PUBLIC && referencesPrivateStruct(obj.getRetType())) {
            semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                    "public function '" + obj.getId() + "' cannot expose private struct in return type",
                    obj.getLineNum(), obj.getSpan(), "private struct in public signature",
                    "declare struct with 'pub' or make function private", null);
        }
        if (obj.getVisibility() == Ast.Visibility.PUBLIC && referencesPrivateEnum(obj.getRetType())) {
            semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                    "public function '" + obj.getId() + "' cannot expose private enum in return type",
                    obj.getLineNum(), obj.getSpan(), "private enum in public signature",
                    "declare enum with 'pub' or make function private", null);
        }
        if (obj.getFormals() != null) {
            for (Ast.Declare.T formal : obj.getFormals()) {
                if (formal instanceof Ast.Declare.DeclareSingle single) {
                    validateStructTypeReference(single.getType(), single.getLineNum(), single.getSpan(), "parameter");
                    validateEnumTypeReference(single.getType(), single.getLineNum(), single.getSpan(), "parameter");
                    if (obj.getVisibility() == Ast.Visibility.PUBLIC && referencesPrivateStruct(single.getType())) {
                        semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                                "public function '" + obj.getId() + "' cannot expose private struct in parameter '" + single.getId() + "'",
                                single.getLineNum(), single.getSpan(), "private struct in public signature",
                                "declare struct with 'pub' or make function private", null);
                    }
                    if (obj.getVisibility() == Ast.Visibility.PUBLIC && referencesPrivateEnum(single.getType())) {
                        semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                                "public function '" + obj.getId() + "' cannot expose private enum in parameter '" + single.getId() + "'",
                                single.getLineNum(), single.getSpan(), "private enum in public signature",
                                "declare enum with 'pub' or make function private", null);
                    }
                }
            }
        }
        if (obj.getLocals() != null) {
            for (Ast.Declare.T local : obj.getLocals()) {
                if (local instanceof Ast.Declare.DeclareSingle single) {
                    validateStructTypeReference(single.getType(), single.getLineNum(), single.getSpan(), "variable declaration");
                    validateEnumTypeReference(single.getType(), single.getLineNum(), single.getSpan(), "variable declaration");
                }
            }
        }

        validatePointerDeclarations(obj.getFormals());
        validatePointerDeclarations(obj.getLocals());
        if (isPointerType(this.typeOfMethodDeclared) && !hasLegalPointees(this.typeOfMethodDeclared)) {
            semanticError(DiagnosticCodes.TYPE_POINTER_ASSIGNMENT,
                    "unsupported pointer type '" + typeName(this.typeOfMethodDeclared)
                            + "': only value scalars and further pointers can be pointed to",
                    obj.getLineNum(), obj.getSpan(), "unsupported pointer type",
                    "pointers to strings or arrays are not supported", null);
        }

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
        if (separator > 0) {
            String alias = methodName.substring(0, separator);
            boolean inNames = importedModuleNames.contains(alias);
            boolean inScope = scopeManager.resolveImport(alias) != null;
            if (!inNames && inScope) {
                System.err.println("[DEBUG] validateImportBinding: alias=" + alias
                        + " inNames=" + inNames + " inScope=" + inScope
                        + " importedModuleNames=" + importedModuleNames);
            }
            if (!inNames && !inScope) {
                System.err.println("[DEBUG] validateImportBinding FAIL: alias=" + alias
                        + " inNames=" + inNames + " inScope=" + inScope
                        + " importedModuleNames=" + importedModuleNames
                        + " span=" + span + " fileName=" + (span == null ? "null" : span.getFileName()));
            }
            if (separator > 0 && scopeManager.resolveImport(methodName.substring(0, separator)) == null
                    && importedModuleNames.contains(methodName.substring(0, separator))) {
                semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                        "module import '" + methodName.substring(0, separator) + "' is not visible in this scope",
                        line, span, "import is out of scope", "declare the import in this lexical scope", null);
            }
        }
    }

    @Override
    public void visit(Ast.Expr.Mul obj) {
        this.visit(obj.getLeft());
        Ast.Type.T leftType = this.currType;
        this.visit(obj.getRight());
        if (rejectPointerArithmetic(obj.getLineNum(), "*", leftType, this.currType, obj.getSpan())) return;
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
        }else if(obj.getType() instanceof Ast.Type.Enum){
            this.currType = obj.getType();
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
        if (rejectPointerArithmetic(obj.getLineNum(), "-", leftType, this.currType, obj.getSpan())) return;
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
            if (placeholder == 'd' && !isIntegerLike(this.currType) && (this.currType == null || this.currType.getKind() != TypeKind.ENUM)) {
                typeError(DiagnosticCodes.TYPE_FORMAT, "int or byte", typeName(this.currType), expressionName(expr),
                        expr.getLineNum(), expr.getSpan(), "printf %d argument", null);
            }
            if (placeholder == 'f'
                    && this.currType.getKind() != TypeKind.FLOAT
                    && this.currType.getKind() != TypeKind.DOUBLE) {
                typeError(DiagnosticCodes.TYPE_FORMAT, "float or double", typeName(this.currType), expressionName(expr),
                        expr.getLineNum(), expr.getSpan(), "printf %f argument", null);
            }
            if (placeholder == 's' && (this.currType == null || this.currType.getKind() != TypeKind.STRING)) {
                typeError(DiagnosticCodes.TYPE_FORMAT, "string", typeName(this.currType), expressionName(expr),
                        expr.getLineNum(), expr.getSpan(), "printf %s argument", null);
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
        if (this.currType == null || this.currType.getKind() != TypeKind.BOOL)
            typeError(DiagnosticCodes.TYPE_OPERATOR, "bool", typeName(this.currType), expressionName(obj.getExpr()),
                    obj.getLineNum(), obj.getSpan(), "operator '!'", "use a boolean expression");
        this.currType = new Ast.Type.Bool();
    }

    // ==================================================== pointer expressions

    @Override
    public void visit(Ast.Type.Pointer obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.Null obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Type.Struct obj) {
        if (resolveStruct(obj.getName()) == null) {
            semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                    "unknown struct type: " + obj.getName(),
                    0, null, "unknown struct",
                    "declare the struct before using it", null);
        }
        this.currType = obj;
    }

    // ==================================================== struct expressions

    @Override
    public void visit(Ast.Expr.Field obj) {
        if (obj.getReceiver() instanceof Ast.Expr.Id id) {
            Ast.EnumDecl enumDecl = resolveEnum(id.getId());
            if (enumDecl != null) {
                if (obj.isPointerBase()) {
                    semanticError(DiagnosticCodes.SEM_GENERAL,
                            "'->' cannot be used with enum '" + id.getId() + "'",
                            obj.getLineNum(), obj.getSpan(), "invalid enum member access",
                            "use '.' to access enum members", null);
                    this.currType = unknownType();
                    return;
                }
                if (obj.getPath().size() != 1) {
                    semanticError(DiagnosticCodes.SEM_GENERAL,
                            "invalid enum member access chain on '" + id.getId() + "'",
                            obj.getLineNum(), obj.getSpan(), "invalid enum member access",
                            "enum members are accessed as Enum.Member", null);
                    this.currType = unknownType();
                    return;
                }
                String memberName = obj.getPath().get(0);
                Ast.EnumMember foundMember = null;
                for (Ast.EnumMember m : enumDecl.getMembers()) {
                    if (m.getName().equals(memberName)) {
                        foundMember = m;
                        break;
                    }
                }
                if (foundMember == null) {
                    semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                            "enum '" + id.getId() + "' has no member '" + memberName + "'",
                            obj.getLineNum(), obj.getSpan(), "unknown enum member",
                            "check the member name in enum '" + id.getId() + "'", null);
                    this.currType = unknownType();
                    return;
                }
                if (enumDecl.getVisibility() == Ast.Visibility.PRIVATE) {
                    if (enumDecl.getDeclaringModule() != null
                            && !java.util.Objects.equals(this.currDeclaringModule, enumDecl.getDeclaringModule())) {
                        semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                                "cannot access member of private enum '" + enumDecl.getName() + "'",
                                obj.getLineNum(), obj.getSpan(), "private enum",
                                "enum is private to its module", null);
                        this.currType = unknownType();
                        return;
                    }
                }
                Ast.Type.Enum enumType = new Ast.Type.Enum(enumDecl.getName());
                this.currType = enumType;
                return;
            }
        }
        // When the receiver is an ArrayAccess (e.g. arr[i].x), the element type
        // was already resolved during ArrayAccess semantic analysis. Use it
        // directly instead of re-looking up the array in the var table.
        if (obj.getReceiver() instanceof Ast.Expr.ArrayAccess arrayAccess
                && arrayAccess.getElementType() != null) {
            visitFieldAccess(arrayAccess.getElementType(), obj.getPath(), obj.isPointerBase(),
                    obj.getLineNum(), obj.getSpan(), obj);
            return;
        }
        // When the receiver is an Id naming a struct array (e.g. arr in arr[0].x),
        // resolve the field path on the array's element type instead of the array itself.
        if (obj.getReceiver() instanceof Ast.Expr.Id idReceiver
                && obj.getPath() != null && !obj.getPath().isEmpty()) {
            MethodVarTable mTable = this.methodVarTable.get(currMethodName);
            if (mTable != null) {
                Ast.Type.T recvType = mTable.get(idReceiver.getId());
                if (recvType != null && isArrayType(recvType)) {
                    Ast.Type.T elemType = getElementType(recvType);
                    if (elemType != null && elemType.getKind() == TypeKind.STRUCT) {
                        visitFieldAccess(elemType, obj.getPath(), obj.isPointerBase(),
                                obj.getLineNum(), obj.getSpan(), obj);
                        return;
                    }
                }
            }
        }
        visitFieldAccess(obj.getReceiver(), obj.getPath(), obj.isPointerBase(), obj.getLineNum(), obj.getSpan(), obj);
    }

    /**
     * Resolves a field access chain starting from {@code receiver} with the given path.
     * Used both by {@link Ast.Expr.Field} and by {@link Ast.Expr.ArrayAccess} when
     * the array element is a struct and the access is like {@code arr[i].x}.
     * Overload that accepts a pre-resolved receiver type avoids a redundant table lookup.
     */
    private void visitFieldAccess(Ast.Expr.T receiver, java.util.List<String> path,
                                   boolean pointerBase, int lineNum, site.ilemon.util.SourceSpan span,
                                   Ast.Expr.T contextNode) {
        this.visit(receiver);
        Ast.Type.T receiverType = this.currType;
        visitFieldAccessFromType(receiverType, path, pointerBase, lineNum, span);
    }

    /**
     * Overload: receiver type is already known (e.g. ArrayAccess's pre-resolved element type).
     */
    private void visitFieldAccess(Ast.Type.T preResolvedReceiverType, java.util.List<String> path,
                                   boolean pointerBase, int lineNum, site.ilemon.util.SourceSpan span,
                                   Ast.Expr.T contextNode) {
        visitFieldAccessFromType(preResolvedReceiverType, path, pointerBase, lineNum, span);
    }

    /**
     * Walks the field path starting from the already-resolved {@code receiverType}.
     */
    private void visitFieldAccessFromType(Ast.Type.T receiverType, java.util.List<String> path,
                                           boolean pointerBase, int lineNum, site.ilemon.util.SourceSpan span) {
        // Pointer base (->) auto-dereferences exactly once.
        if (pointerBase) {
            if (!isPointerType(receiverType)
                    || !(((Ast.Type.Pointer) receiverType).getPointee().getKind() == TypeKind.STRUCT)) {
                semanticError(DiagnosticCodes.SEM_GENERAL,
                        "'->' requires a pointer to a struct, but the operand has type " + typeName(receiverType),
                        lineNum, span, "invalid pointer field access",
                        "use '.' for struct values and '->' for struct pointers", null);
                this.currType = unknownType();
                return;
            }
            receiverType = ((Ast.Type.Pointer) receiverType).getPointee();
        }
        for (int i = 0; i < path.size(); i++) {
            String fieldName = path.get(i);
            if (receiverType == null || receiverType.getKind() != TypeKind.STRUCT) {
                semanticError(DiagnosticCodes.SEM_GENERAL,
                        "field access '.' requires a struct value, but '" + path.get(0)
                                + "' chain reaches type " + typeName(receiverType),
                        lineNum, span, "invalid field access",
                        "field access is only valid on struct values", null);
                this.currType = unknownType();
                return;
            }
            Ast.StructDecl structDecl = resolveStruct(((Ast.Type.Struct) receiverType).getName());
            if (structDecl == null) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                        "unknown struct type: " + ((Ast.Type.Struct) receiverType).getName(),
                        lineNum, span, "unknown struct", null, null);
                this.currType = unknownType();
                return;
            }
            boolean isCrossModule = structDecl.getDeclaringModule() != null
                    && !java.util.Objects.equals(this.currDeclaringModule, structDecl.getDeclaringModule());
            if (isCrossModule) {
                if (structDecl.getVisibility() == Ast.Visibility.PRIVATE) {
                    semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                            "cannot access field '" + fieldName + "' of private struct '" + structDecl.getName() + "'",
                            lineNum, span, "private struct",
                            "struct is private to its declaring module", null);
                    this.currType = unknownType();
                    return;
                }
                Ast.Visibility fieldVis = structDecl.fieldVisibility(fieldName);
                if (fieldVis == Ast.Visibility.PRIVATE) {
                    semanticError(DiagnosticCodes.SEM_INVALID_SCOPE,
                            "cannot access private field '" + fieldName + "' of struct '" + structDecl.getName() + "'",
                            lineNum, span, "private field",
                            "field is private to its declaring module", null);
                    this.currType = unknownType();
                    return;
                }
            }
            Ast.Type.T fieldType = structDecl.fieldType(fieldName);
            if (fieldType == null) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                        "struct '" + structDecl.getName() + "' has no field '" + fieldName + "'",
                        lineNum, span, "unknown field",
                        "the field is not declared in the struct", null);
                this.currType = unknownType();
                return;
            }
            // Follow struct-valued fields toward the next path link.
            receiverType = fieldType;
            if (i == path.size() - 1) {
                this.currType = fieldType;
            }
        }
    }

    @Override
    public void visit(Ast.Stmt.FieldAssign obj) {
        // Walk to the ultimate root of the field chain to validate it's a local variable.
        // The receiver may be an Id, an ArrayAccess (for arr[i].field), or another Field.
        Ast.Expr.T root = obj.getTarget().getReceiver();
        while (root instanceof Ast.Expr.Field f) {
            root = f.getReceiver();
        }
        if (root instanceof Ast.Expr.ArrayAccess arrayAccess) {
            // arr[i].field = ... — clear the array's "may be unassigned" flag.
            String arrName = arrayAccess.getArrayName();
            if (!arrName.isEmpty()) {
                this.currMethodLocalVar.remove(arrName);
            }
        } else if (root instanceof Ast.Expr.Id rootId) {
            if (resolveEnum(rootId.getId()) != null) {
                String memberName = obj.getTarget().getPath().isEmpty() ? "" : obj.getTarget().getPath().get(0);
                semanticError(DiagnosticCodes.SEM_CONST_IMMUTABLE,
                        "cannot assign to enum member '" + rootId.getId() + "." + memberName + "': enum members are immutable",
                        obj.getLineNum(), obj.getSpan(), "immutable enum member",
                        "enum members cannot be reassigned", null);
                return;
            }
            MethodVarTable assignTable = this.methodVarTable.get(currMethodName);
            boolean isLocal = assignTable != null && assignTable.get(rootId.getId()) != null;
            if (!isLocal && resolveConst(rootId.getId()) != null) {
                semanticError(DiagnosticCodes.SEM_CONST_IMMUTABLE,
                        "cannot assign to field of constant '" + rootId.getId() + "': constants are immutable",
                        obj.getLineNum(), obj.getSpan(), "immutable constant",
                        "fields of constants cannot be modified", null);
                return;
            }
        }
        // Writing through the chain initializes the base struct the same way a
        // whole-assignment would; clear the may-be-unassigned flag first so
        // the receiver visit does not report a premature use.
        if (obj.getTarget().getReceiver() instanceof Ast.Expr.Id base) {
            this.currMethodLocalVar.remove(base.getId());
        }
        // Resolve the target field type first (also validates the chain).
        this.visit(obj.getTarget());
        Ast.Type.T targetType = this.currType;
        if (isPointerType(targetType)) {
            // Rebinding a pointer field through '.' would need alias tracking
            // that the current pointer model does not have.
            semanticError(DiagnosticCodes.TYPE_POINTER_WRITE,
                    "cannot assign to pointer field '" + typeName(targetType) + "': field pointer writes are not supported",
                    obj.getLineNum(), obj.getSpan(), "unsupported pointer write",
                    "assign through the pointer instead: p->field = value", null);
            return;
        }
        this.allowEnumIntAssignment = true;
        this.visit(obj.getExpr());
        Ast.Type.T valueType = this.currType;
        boolean allowed = isAssignable(targetType, valueType, obj.getExpr());
        this.allowEnumIntAssignment = false;
        if (!allowed) {
            if (!rangeErrorIfNeeded(targetType, valueType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                    "field assignment")) {
                if (!shortRangeErrorIfNeeded(targetType, valueType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                        "field assignment")) {
                    typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(targetType), typeName(valueType),
                            expressionName(obj.getExpr()), obj.getLineNum(), obj.getSpan(),
                            "field assignment", null);
                }
            }
        }
    }

    @Override
    public void visit(Ast.Expr.Null obj) {
        // null is a valid pointer literal only; context checks it against a
        // pointer target type before any use.
        this.currType = new Ast.Type.Null();
    }

    @Override
    public void visit(Ast.Expr.AddressOf obj) {
        Ast.Expr.T operand = obj.getOperand();
        Ast.Type.T operandType = null;
        if (operand instanceof Ast.Expr.Id id) {
            MethodVarTable table = this.methodVarTable.get(currMethodName);
            Ast.Type.T localType = table == null ? null : table.get(id.getId());
            if (localType == null) {
                // Module bindings, constants, and unknown names are not addressable.
                if (resolveConst(id.getId()) != null || importedModuleNames.contains(id.getId())
                        || id.getId().contains("_")) {
                    semanticError(DiagnosticCodes.TYPE_POINTER_ADDRESS_OF,
                            "cannot take the address of '" + id.getId() + "': constants and imports are not addressable",
                            obj.getLineNum(), obj.getSpan(), "invalid address-of",
                            "only local variables can be referenced", null);
                } else {
                    semanticError(DiagnosticCodes.TYPE_POINTER_ADDRESS_OF,
                            "cannot take the address of '" + id.getId() + "': the variable is not declared in this scope",
                            obj.getLineNum(), obj.getSpan(), "invalid address-of",
                            "only local variables can be referenced", null);
                }
                this.currType = unknownType();
                return;
            }
            if (currMethodFormals.contains(id.getId())) {
                semanticError(DiagnosticCodes.TYPE_POINTER_ADDRESS_OF,
                        "cannot take the address of parameter '" + id.getId()
                                + "': parameters are value copies without stable addressable storage",
                        obj.getLineNum(), obj.getSpan(), "invalid address-of",
                        "only local variables can be referenced", null);
                this.currType = unknownType();
                return;
            }
            operandType = localType;
            if (isManagedValueType(operandType)) {
                semanticError(DiagnosticCodes.TYPE_POINTER_ADDRESS_OF,
                        "cannot take the address of '" + id.getId() + "': " + typeName(operandType)
                                + " values are reference-managed and not addressable",
                        obj.getLineNum(), obj.getSpan(), "invalid address-of",
                        "address-of is only supported for value scalars and pointers", null);
                this.currType = unknownType();
                return;
            }
        } else {
            semanticError(DiagnosticCodes.TYPE_POINTER_ADDRESS_OF,
                    "cannot take the address of this expression: address-of requires a local variable",
                    obj.getLineNum(), obj.getSpan(), "invalid address-of",
                    "temporaries and computed values have no stable storage", null);
            this.currType = unknownType();
            return;
        }
        this.currType = new Ast.Type.Pointer(operandType);
    }

    @Override
    public void visit(Ast.Expr.Deref obj) {
        this.visit(obj.getOperand());
        Ast.Type.T pointerType = this.currType;
        if (!isPointerType(pointerType)) {
            semanticError(DiagnosticCodes.TYPE_POINTER_DEREF,
                    "cannot dereference non-pointer type " + typeName(pointerType),
                    obj.getLineNum(), obj.getSpan(), "invalid dereference",
                    "the '*' operator requires a pointer operand", null);
            this.currType = unknownType();
            return;
        }
        this.currType = ((Ast.Type.Pointer) pointerType).getPointee();
    }

    @Override
    public void visit(Ast.Stmt.DerefAssign obj) {
        Ast.Expr.Deref target = obj.getTarget();
        // Validate the dereference chain (pointer levels down to the pointee).
        this.visit(target);
        Ast.Type.T targetType = this.currType;
        this.visit(obj.getExpr());
        Ast.Type.T valueType = this.currType;
        // Escape analysis: storing the address of a local through a dereference
        // allows the address to escape the current frame (e.g., *global = &local).
        // This check runs first to give a more specific error for the escape case.
        if (exprMayPointToLocal(obj.getExpr())) {
            semanticError(DiagnosticCodes.SEM_POINTER_ESCAPE,
                    "cannot store pointer to local variable through dereference: the referenced storage dies when the function returns",
                    obj.getLineNum(), obj.getSpan(), "dangling pointer escape",
                    "do not store the address of a local variable through a pointer", "store the value directly or use a pointer received as a parameter");
            return;
        }
        // General pointer write prohibition: storing any pointer through dereference
        // is not supported (use direct pointer assignment instead).
        if (isPointerType(valueType) || valueType != null && valueType.getKind() == TypeKind.NULL) {
            semanticError(DiagnosticCodes.TYPE_POINTER_WRITE,
                    "assignment through a dereference must store a value scalar, not a pointer",
                    obj.getLineNum(), obj.getSpan(), "unsupported pointer write",
                    "writing a pointer through '*ptr = ...' is not supported; assign the pointer variable directly", null);
            return;
        }
        if (targetType == null || !isAssignable(targetType, valueType, obj.getExpr())) {
            if (targetType != null && !rangeErrorIfNeeded(targetType, valueType, obj.getExpr(),
                    obj.getLineNum(), obj.getSpan(), "dereference assignment")) {
                typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(targetType), typeName(valueType),
                        expressionName(obj.getExpr()), obj.getLineNum(), obj.getSpan(), "dereference assignment", null);
            }
        }
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
            if (obj.getExpr() != null) {
                error(obj.getLineNum(), "void method cannot return a value");
            }
            return;
        }
        if (obj.getExpr() == null) {
            typeError(DiagnosticCodes.TYPE_RETURN, typeName(typeOfMethodDeclared), "void",
                    "return;", obj.getLineNum(), obj.getSpan(), "return statement", "non-void method must return a value");
            return;
        }
        this.visit(obj.getExpr());
        if (isPointerType(typeOfMethodDeclared) && exprMayPointToLocal(obj.getExpr())) {
            semanticError(DiagnosticCodes.SEM_POINTER_ESCAPE,
                    "cannot return pointer to local variable: the referenced storage dies when the function returns",
                    obj.getLineNum(), obj.getSpan(), "dangling pointer",
                    "do not return the address of a local variable", "return the value or a pointer received as a parameter");
            return;
        }
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
        MethodVarTable whileMTable = this.methodVarTable.get(currMethodName);
        if (whileMTable != null) whileMTable.enterScope();
        loopDepth++;
        this.currMethodLocalVar = new HashSet<>(before);
        this.visit(obj.getBody());
        loopDepth--;
        this.currMethodLocalVar = before;
        if (whileMTable != null) {
            Set<String> leavingVars = whileMTable.currentScopeNames();
            this.currMethodLocalVar.removeAll(leavingVars);
            whileMTable.exitScope(leavingVars);
        }
    }

    @Override
    public void visit(Ast.Stmt.For obj) {
        MethodVarTable mTable = this.methodVarTable.get(currMethodName);
        if (mTable != null) mTable.enterScope();
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
        if (mTable != null) {
            Set<String> leavingVars = mTable.currentScopeNames();
            this.currMethodLocalVar.removeAll(leavingVars);
            mTable.exitScope(leavingVars);
        }
    }

    @Override
    public void visit(Ast.Stmt.Break obj) {
        if (loopDepth <= 0 && switchDepth <= 0)
            error(obj.getLineNum(), "break statement must be inside a loop or switch");
    }

    @Override
    public void visit(Ast.Stmt.Continue obj) {
        if (loopDepth <= 0)
            error(obj.getLineNum(), "continue statement must be inside a loop");
    }

    @Override
    public void visit(Ast.Stmt.Switch obj) {
        MethodVarTable mTable = this.methodVarTable.get(currMethodName);
        if (mTable != null) mTable.enterScope();
        HashSet<String> before = new HashSet<>(this.currMethodLocalVar);
        this.assignedInAllCases = null;

        // Subject type: integer-like (byte/short/char/int/long) or enum.
        this.visit(obj.getSubject());
        Ast.Type.T subjectType = this.currType;
        boolean subjectIsEnum = subjectType != null && subjectType.getKind() == TypeKind.ENUM;
        boolean subjectIsInt = isIntegerLike(subjectType);
        if (!subjectIsEnum && !subjectIsInt) {
            typeError(DiagnosticCodes.TYPE_CONDITION, "integer or enum",
                    typeName(subjectType), expressionName(obj.getSubject()),
                    obj.getSubject().getLineNum(), obj.getSubject().getSpan(),
                    "switch subject", "switch requires an integer or enum value");
        }

        java.util.Set<Long> seenValues = new java.util.HashSet<>();
        java.util.Set<String> seenEnumMembers = new java.util.HashSet<>();
        boolean seenDefault = false;
        ArrayList<Ast.Stmt.CaseClause> clauses = obj.getClauses() == null
                ? new ArrayList<>() : obj.getClauses();

        for (Ast.Stmt.CaseClause clause : clauses) {
            if (clause.isDefault()) {
                if (seenDefault) {
                    semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION,
                            "duplicate 'default' clause in switch; at most one default is allowed",
                            clause.getLineNum(), clause.getSpan(), "duplicate default",
                            "the switch already has a default clause",
                            "remove the extra 'default' clause");
                    continue;
                }
                seenDefault = true;
            } else {
                Ast.Expr.T label = clause.getLabel();
                this.visit(label);
                Ast.Type.T labelType = this.currType;
                SwitchLabel resolved = resolveSwitchCaseLabel(label);
                if (subjectIsEnum) {
                    // Case label must be a constant member of the subject's enum.
                    if (resolved == null || resolved.enumName() == null) {
                        semanticError(DiagnosticCodes.SEM_INVALID_SYMBOL_USAGE,
                                "case label must be an enum member constant, but found " + typeName(labelType),
                                label.getLineNum(), label.getSpan(), "invalid case label",
                                "switch on an enum requires case labels of its own members",
                                "use a member of " + ((Ast.Type.Enum) subjectType).getSimpleName()
                                        + " as the case label");
                        continue;
                    }
                    if (!seenEnumMembers.add(resolved.enumName() + "." + resolved.memberName())) {
                        semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION,
                                "duplicate case in switch: " + resolved.memberName() + " already handled",
                                clause.getLineNum(), clause.getSpan(), "duplicate case",
                                "the case value was already handled by an earlier case",
                                "remove the duplicate case");
                        continue;
                    }
                } else {
                    // Integer switch: label must resolve to a compile-time integer.
                    // An enum member is not an integer constant here, even though
                    // its declared value is an int.
                    Long value = resolved == null || resolved.enumName() != null
                            ? null : resolved.intValue();
                    if (value == null) {
                        semanticError(DiagnosticCodes.SEM_INVALID_SYMBOL_USAGE,
                                "case label must be a constant integer, but found " + typeName(labelType),
                                label.getLineNum(), label.getSpan(), "non-constant case label",
                                "case labels must be integer literals, enum members, or constants",
                                "use a compile-time integer constant as the case label");
                        continue;
                    }
                    if (!seenValues.add(value)) {
                        semanticError(DiagnosticCodes.SEM_DUPLICATE_DECLARATION,
                                "duplicate case in switch: " + value + " already handled",
                                clause.getLineNum(), clause.getSpan(), "duplicate case",
                                "the case value was already handled by an earlier case",
                                "remove the duplicate case");
                        continue;
                    }
                }
            }
            // Case body: scoped to this clause (locals declared here do not
            // leak into sibling clauses); break inside is legal and targets
            // this switch (or the innermost enclosing loop).
            HashSet<String> clauseBefore = new HashSet<>(this.currMethodLocalVar);
            this.currMethodLocalVar = new HashSet<>(clauseBefore);
            switchDepth++;
            if (clause.getBody() != null) {
                for (Ast.Stmt.T stmt : clause.getBody()) {
                    this.visit(stmt);
                }
            }
            switchDepth--;
            // Track which previously-unassigned variables became assigned in this case.
            Set<String> assignedThisCase = new HashSet<>(clauseBefore);
            assignedThisCase.removeAll(this.currMethodLocalVar);
            if (assignedInAllCases == null) {
                assignedInAllCases = new HashSet<>(assignedThisCase);
            } else {
                assignedInAllCases.retainAll(assignedThisCase);
            }
            this.currMethodLocalVar = clauseBefore;
        }

        // A variable is definitely initialized after the switch only if it was
        // assigned in every case path (including default).
        this.currMethodLocalVar = new HashSet<>(before);
        if (assignedInAllCases != null) {
            this.currMethodLocalVar.removeAll(assignedInAllCases);
        }
        this.assignedInAllCases = null;
        if (mTable != null) {
            Set<String> leavingVars = mTable.currentScopeNames();
            this.currMethodLocalVar.removeAll(leavingVars);
            mTable.exitScope(leavingVars);
        }
    }

    /** Resolved switch case label: an enum member, or a compile-time integer. */
    private record SwitchLabel(String enumName, String memberName, Long intValue) {}

    /**
     * Resolves a case label directly from its AST shape: integer literals
     * (including negative forms), enum members ({@code Enum.MEMBER}), or named
     * constants of integer type. Returns null when the label is not a valid
     * compile-time constant.
     */
    private SwitchLabel resolveSwitchCaseLabel(Ast.Expr.T label) {
        if (label == null) {
            return null;
        }
        // Negative literal forms: -N / 0-N (parser-generated shapes).
        Long direct = integralLiteralValue(label);
        if (direct != null) {
            return new SwitchLabel(null, null, direct);
        }
        // Enum member: Color.RED, or a bare member name (ADD_OP) resolved
        // through the enum member table.
        if (label instanceof Ast.Expr.Field field
                && field.getReceiver() instanceof Ast.Expr.Id receiver
                && field.getPath().size() == 1) {
            Ast.EnumDecl enumDecl = resolveEnum(receiver.getId());
            if (enumDecl != null) {
                String memberName = field.getPath().get(0);
                Ast.EnumMember member = enumDecl.getMember(memberName);
                if (member == null) {
                    return null;
                }
                return new SwitchLabel(enumDecl.getName(), memberName, (long) member.getValue());
            }
        }
        if (label instanceof Ast.Expr.Id id) {
            // Bare enum member (no enum prefix).
            EnumMemberInfo info = enumMemberTable.get(id.getId());
            if (info != null) {
                return new SwitchLabel(info.enumName(), info.memberName(), (long) info.value());
            }
            // Named constant of integer type.
            Ast.ConstDecl constant = resolveConst(id.getId());
            if (constant != null && constant.getType() != null && isIntegerLike(constant.getType())) {
                try {
                    return new SwitchLabel(null, null, Long.parseLong(String.valueOf(constant.getResolvedValue())));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
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
        if (statement instanceof Ast.Stmt.Break || statement instanceof Ast.Stmt.Continue) {
            // break/continue end the normal flow of the enclosing construct.
            return new FlowResult(false, false);
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
        if (statement instanceof Ast.Stmt.Switch switchStmt) {
            // A switch returns on all paths only when every case body returns
            // (or ends in a terminator) AND a default clause exists. An empty
            // case body falls through into the next clause, so it terminates
            // when the next clause does.
            ArrayList<Ast.Stmt.CaseClause> clauses = switchStmt.getClauses();
            if (clauses == null || clauses.isEmpty()) {
                return new FlowResult(true, false);
            }
            boolean seenDefault = false;
            // Compute per-clause termination from the clause end.
            boolean[] clauseTerminates = new boolean[clauses.size()];
            for (int i = clauses.size() - 1; i >= 0; i--) {
                Ast.Stmt.CaseClause clause = clauses.get(i);
                if (clause.isDefault()) {
                    seenDefault = true;
                }
                if (clause.getBody() == null || clause.getBody().isEmpty()) {
                    // Fallthrough: inherits the next clause's termination; the
                    // last empty clause falls to the switch exit (no return).
                    clauseTerminates[i] = i + 1 < clauses.size() && clauseTerminates[i + 1];
                } else {
                    FlowResult bodyFlow = flowOfStatements(clause.getBody());
                    if (bodyFlow.mustReturn) {
                        clauseTerminates[i] = true;
                    } else if (!bodyFlow.canCompleteNormally) {
                        // Body terminated via break/continue: falls through or
                        // exits the switch — never returns by itself.
                        clauseTerminates[i] = false;
                    } else {
                        // Body completes normally without a terminator: the
                        // flow falls into the next clause; the clause returns
                        // only when the next clause does.
                        clauseTerminates[i] = i + 1 < clauses.size() && clauseTerminates[i + 1];
                    }
                }
            }
            // Without a default the subject may match no case, so the switch
            // can complete normally; with a default and all case bodies
            // terminating, the switch must return.
            boolean allTerminate = true;
            for (boolean ct : clauseTerminates) {
                if (!ct) {
                    allTerminate = false;
                    break;
                }
            }
            boolean mustReturn = allTerminate && seenDefault;
            // canCompleteNormally is the negation only for the must-return
            // case; a switch without a default always can complete normally,
            // even when every clause body terminates.
            return new FlowResult(!mustReturn, mustReturn);
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
            if (placeholder == 'd' || placeholder == 'f' || placeholder == 's') {
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

    // ==================================================== pointer helpers

    private boolean isPointerType(Ast.Type.T type) {
        return type != null && type.getKind() == TypeKind.POINTER;
    }

    private boolean isManagedValueType(Ast.Type.T type) {
        if (type == null) {
            return false;
        }
        return isArrayType(type) || type.getKind() == TypeKind.STRING || type.getKind() == TypeKind.VOID;
    }

    /** True for struct-typed values (passed and returned by value). */
    private boolean isStructType(Ast.Type.T type) {
        return type != null && type.getKind() == TypeKind.STRUCT;
    }

    private boolean isAllowedPointee(Ast.Type.T type) {
        if (isPointerType(type)) {
            return true; // multi-level pointers are legal
        }
        if (type == null) {
            return false;
        }
        return switch (type.getKind()) {
            case BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, BOOL, ENUM -> true;
            case STRUCT -> resolveStruct(((Ast.Type.Struct) type).getName()) != null;
            default -> false;
        };
    }

    private boolean hasLegalPointees(Ast.Type.T type) {
        if (!isPointerType(type)) {
            return true;
        }
        Ast.Type.T pointee = ((Ast.Type.Pointer) type).getPointee();
        if (isPointerType(pointee)) {
            return hasLegalPointees(pointee);
        }
        return isAllowedPointee(pointee);
    }

    /** True when both pointers point at the same (recursively compared) type. */
    private boolean pointerTypesEqual(Ast.Type.T left, Ast.Type.T right) {
        if (!isPointerType(left) || !isPointerType(right)) {
            return false;
        }
        Ast.Type.T leftPointee = ((Ast.Type.Pointer) left).getPointee();
        Ast.Type.T rightPointee = ((Ast.Type.Pointer) right).getPointee();
        if (isPointerType(leftPointee) || isPointerType(rightPointee)) {
            return pointerTypesEqual(leftPointee, rightPointee);
        }
        if (leftPointee.getKind() == TypeKind.STRUCT && rightPointee.getKind() == TypeKind.STRUCT) {
            return ((Ast.Type.Struct) leftPointee).getSimpleName().equals(((Ast.Type.Struct) rightPointee).getSimpleName());
        }
        if (leftPointee.getKind() == TypeKind.ENUM && rightPointee.getKind() == TypeKind.ENUM) {
            return ((Ast.Type.Enum) leftPointee).getSimpleName().equals(((Ast.Type.Enum) rightPointee).getSimpleName());
        }
        return leftPointee.getKind() == rightPointee.getKind();
    }

    /**
     * Rejects every arithmetic operator applied to pointers (and to {@code null}).
     * Pointer arithmetic is deliberately not implemented; it must fail loudly
     * and consistently on both backends instead of being partially supported.
     */
    private boolean rejectPointerArithmetic(int lineNum, String operator, Ast.Type.T left,
                                            Ast.Type.T right, site.ilemon.util.SourceSpan span) {
        boolean leftPointer = isPointerType(left) || left != null && left.getKind() == TypeKind.NULL;
        boolean rightPointer = isPointerType(right) || right != null && right.getKind() == TypeKind.NULL;
        if (!leftPointer && !rightPointer) {
            return false;
        }
        semanticError(DiagnosticCodes.TYPE_POINTER_ARITHMETIC,
                "invalid pointer arithmetic: operator '" + operator + "' cannot be applied to "
                        + typeName(left) + " and " + typeName(right),
                lineNum, span, "unsupported pointer operation",
                "pointer arithmetic (ptr + n, ptr - n, ...) is not supported by LemonC", null);
        this.currType = unknownType();
        return true;
    }

    /**
     * Conservative alias/escape predicate: does evaluating this expression
     * produce a pointer value that may reference the current frame's locals?
     * (Address-of is a direct source; pointer locals holding such an address
     * are tracked monotonically in {@link #localAddrTaint}.)
     */
    private boolean exprMayPointToLocal(Ast.Expr.T expr) {
        if (expr instanceof Ast.Expr.AddressOf) {
            return true;
        }
        if (expr instanceof Ast.Expr.Id id) {
            return this.localAddrTaint.contains(id.getId());
        }
        if (expr instanceof Ast.Expr.Deref deref) {
            return exprMayPointToLocal(deref.getOperand());
        }
        if (expr instanceof Ast.Expr.Call call) {
            if (call.getInputParams() != null) {
                for (Ast.Expr.T argument : call.getInputParams()) {
                    if (exprMayPointToLocal(argument)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void validatePointerDeclarations(java.util.List<Ast.Declare.T> declarations) {
        if (declarations == null) {
            return;
        }
        for (Ast.Declare.T declaration : declarations) {
            if (!(declaration instanceof Ast.Declare.DeclareSingle single)) {
                continue;
            }
            if (isPointerType(single.getType()) && !hasLegalPointees(single.getType())) {
                semanticError(DiagnosticCodes.TYPE_POINTER_ASSIGNMENT,
                        "unsupported pointer type '" + typeName(single.getType())
                                + "': only value scalars and further pointers can be pointed to",
                        single.getLineNum(), single.getSpan(), "unsupported pointer type",
                        "pointers to strings or arrays are not supported", null);
            }
        }
    }

    private boolean isMatch(Ast.Type.T target,Ast.Type.T curr){
        if( target == null || curr == null )
            return false;
        // Pointer compatibility: identical pointee chains, or null against any
        // pointer. Everything else is a mismatch (never numeric promotion).
        if (isPointerType(target) || isPointerType(curr)
                || target.getKind() == TypeKind.NULL || curr.getKind() == TypeKind.NULL) {
            if (isPointerType(target) && isPointerType(curr)) {
                return pointerTypesEqual(target, curr);
            }
            if ((isArrayType(target) && curr.getKind() == TypeKind.NULL)
                    || (target.getKind() == TypeKind.NULL && isArrayType(curr))) {
                return true;
            }
            // null is compatible with any pointer type, on either side; two
            // nulls are also mutually compatible.
            return (isPointerType(target) && curr.getKind() == TypeKind.NULL)
                    || (target.getKind() == TypeKind.NULL && isPointerType(curr))
                    || (target.getKind() == TypeKind.NULL && curr.getKind() == TypeKind.NULL);
        }
        if(target.getKind() == curr.getKind()){
            // Struct values match only when they name the same struct.
            if (target.getKind() == TypeKind.STRUCT) {
                Ast.Type.Struct tStruct = (Ast.Type.Struct) target;
                Ast.Type.Struct cStruct = (Ast.Type.Struct) curr;
                if (tStruct.getModuleAlias() != null && cStruct.getModuleAlias() != null
                        && !tStruct.getModuleAlias().equals(cStruct.getModuleAlias())) {
                    return false;
                }
                return tStruct.getSimpleName().equals(cStruct.getSimpleName());
            }
            if (target.getKind() == TypeKind.ENUM) {
                Ast.Type.Enum tEnum = (Ast.Type.Enum) target;
                Ast.Type.Enum cEnum = (Ast.Type.Enum) curr;
                if (tEnum.getModuleAlias() != null && cEnum.getModuleAlias() != null
                        && !tEnum.getModuleAlias().equals(cEnum.getModuleAlias())) {
                    return false;
                }
                return tEnum.getSimpleName().equals(cEnum.getSimpleName());
            }
            return true;
        }
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
        // Enums are representationally ints: allow assignment to int fields and vice versa
        if (allowEnumIntAssignment) {
            if (target.getKind() == TypeKind.ENUM && curr.getKind() == TypeKind.INT)
                return true;
            if (target.getKind() == TypeKind.INT && curr.getKind() == TypeKind.ENUM)
                return true;
        }
        return false;
    }

    @Override
    public void visit(Ast.Expr.InitializerList obj) {
        if (obj == null) return;
        for (Ast.Expr.T elem : obj.getElements()) {
            this.visit(elem);
        }
        this.currType = obj.getType();
    }

    private boolean isAssignable(Ast.Type.T target, Ast.Type.T actual, Ast.Expr.T expression) {
        if (expression instanceof Ast.Expr.InitializerList initList && target != null && target.getKind() == TypeKind.STRUCT) {
            Ast.StructDecl decl = resolveStruct(((Ast.Type.Struct) target).getName());
            if (decl == null || decl.getFields().size() != initList.getElements().size()) {
                return false;
            }
            for (int i = 0; i < decl.getFields().size(); i++) {
                if (!(decl.getFields().get(i) instanceof Ast.Declare.DeclareSingle field)) {
                    return false;
                }
                Ast.Expr.T elem = initList.getElements().get(i);
                this.visit(elem);
                Ast.Type.T elemType = (elem instanceof Ast.Expr.Call call) ? call.getReturnType() : this.currType;
                if (!isAssignable(field.getType(), elemType, elem)) {
                    return false;
                }
            }
            initList.setType(target);
            return true;
        }
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

    private void validateArrayInitializer(Ast.Type.T arrayType, Ast.Expr.InitializerList initList, int lineNum, site.ilemon.util.SourceSpan span) {
        Ast.Type.T elementType = getElementType(arrayType);
        if (elementType == null) {
            typeError(DiagnosticCodes.TYPE_ARRAY_INIT_ELEMENT_TYPE, "supported array element type",
                    typeName(arrayType), "array initializer", lineNum, span,
                    "array initializer for '" + arrayType + "'", "only primitive numeric, bool, char, and string arrays are supported");
            return;
        }
        int expectedSize = -1;
        if (arrayType instanceof Ast.Type.IntArray ia) expectedSize = ia.getSize();
        else if (arrayType instanceof Ast.Type.ByteArray ba) expectedSize = ba.getSize();
        else if (arrayType instanceof Ast.Type.ShortArray sa) expectedSize = sa.getSize();
        else if (arrayType instanceof Ast.Type.CharArray ca) expectedSize = ca.getSize();
        else if (arrayType instanceof Ast.Type.LongArray la) expectedSize = la.getSize();
        else if (arrayType instanceof Ast.Type.FloatArray fa) expectedSize = fa.getSize();
        else if (arrayType instanceof Ast.Type.DoubleArray da) expectedSize = da.getSize();
        else if (arrayType instanceof Ast.Type.BoolArray boa) expectedSize = boa.getSize();
        else if (arrayType instanceof Ast.Type.StringArray sta) expectedSize = sta.getSize();
        else if (arrayType instanceof Ast.Type.StructArray structArray) expectedSize = structArray.getSize();

        java.util.List<Ast.Expr.T> elements = initList.getElements();
        int count = elements.size();
        if (expectedSize >= 0 && count > expectedSize) {
            semanticError(DiagnosticCodes.TYPE_ARRAY_INIT_SIZE_MISMATCH,
                    String.format("array initializer has %d element(s) but the declared array size is %d", count, expectedSize),
                    lineNum, span != null ? span : initList.getSpan(), "array initializer",
                    "reduce the number of initializer elements to match the declared size",
                    "remove " + (count - expectedSize) + " extra element(s)");
        }
        for (int i = 0; i < count; i++) {
            Ast.Expr.T elem = elements.get(i);
            this.visit(elem);
            Ast.Type.T elemType = (elem instanceof Ast.Expr.Call call) ? call.getReturnType() : this.currType;
            if (!isAssignable(elementType, elemType, elem)) {
                typeError(DiagnosticCodes.TYPE_ARRAY_INIT_ELEMENT_TYPE, typeName(elementType), typeName(elemType),
                        "initializer element " + i, elem.getLineNum(), elem.getSpan(),
                        "array initializer for '" + arrayType + "'", "element type must match the array element type");
            }
        }
        initList.setType(arrayType);
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
        if (expression instanceof Ast.Expr.UnaryMinus um
                && um.getExp() instanceof Ast.Expr.Number number) {
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
                || kind == TypeKind.SHORT_ARRAY || kind == TypeKind.CHAR_ARRAY || kind == TypeKind.LONG_ARRAY
                || kind == TypeKind.STRUCT_ARRAY;
    }

    /**
     * Validate equality operators (== / !=): types must match.
     */
    private void checkComparison(Ast.Expr.T left, Ast.Expr.T right, String op, int lineNum) {
        this.visit(left);
        Ast.Type.T leftType = this.currType;
        this.visit(right);
        if (isArrayType(leftType) || isArrayType(this.currType)) {
            boolean leftNull = leftType != null && leftType.getKind() == TypeKind.NULL;
            boolean rightNull = this.currType != null && this.currType.getKind() == TypeKind.NULL;
            if (!(leftNull || rightNull)) {
                error(lineNum, String.format("comparison operator '%s' does not support array operands: left is %s, right is %s",
                        op, typeName(leftType), typeName(this.currType)));
            }
        }
        if (promoteNumeric(leftType, this.currType) == null && !isMatch(leftType, this.currType)) {
            // Allow enum↔int in equality comparison (e.g. comparing a struct field with an int array value).
            boolean enumIntMatch =
                    ((leftType != null && leftType.getKind() == TypeKind.ENUM && this.currType != null && this.currType.getKind() == TypeKind.INT)
                     || (leftType != null && leftType.getKind() == TypeKind.INT && this.currType != null && this.currType.getKind() == TypeKind.ENUM));
            if (!enumIntMatch) {
                typeError(DiagnosticCodes.TYPE_OPERATOR, typeName(leftType), typeName(this.currType), "comparison expression",
                        lineNum, left.getSpan(), "comparison operator '" + op + "'", null);
            } else {
                // Comparison result is always bool
                this.currType = new Ast.Type.Bool();
                return;
            }
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
        // Allow enum↔int compatibility for function-call arguments
        this.allowEnumIntAssignment = true;
        try {
            for (int i = 0; i < inputParams.size(); i++) {
                this.visit(inputParams.get(i));
                Ast.Type.T actualType = this.currType;
                this.visit(method.getFormals().get(i));
                Ast.Type.T expectedType = this.currType;
                if (!isAssignable(expectedType, actualType, inputParams.get(i))) {
                    Ast.Expr.T argument = inputParams.get(i);
                    if (!rangeErrorIfNeeded(expectedType, actualType, argument, argument.getLineNum(), argument.getSpan(),
                            "argument " + (i + 1) + " of '" + methodName + "'")) {
                        // Allow enum↔int for function arguments when flag is set
                        boolean enumIntMatch = allowEnumIntAssignment
                                && ((expectedType != null && expectedType.getKind() == TypeKind.ENUM && actualType != null && actualType.getKind() == TypeKind.INT)
                                 || (expectedType != null && expectedType.getKind() == TypeKind.INT && actualType != null && actualType.getKind() == TypeKind.ENUM));
                        if (!enumIntMatch) {
                            typeError(DiagnosticCodes.TYPE_ARGUMENT, typeName(expectedType), typeName(actualType), expressionName(argument),
                                    argument.getLineNum(), argument.getSpan(), "argument " + (i + 1) + " of '" + methodName + "'", null);
                        }
                    }
                }
            }
        } finally {
            this.allowEnumIntAssignment = false;
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
    public void visit(Ast.Type.StructArray obj) {
        this.currType = obj;
    }

    @Override
    public void visit(Ast.Expr.ArrayAccess obj) {
        Ast.Type.T arrayType;
        if (obj.getFieldTarget() != null) {
            Ast.Expr.Field fieldTarget = obj.getFieldTarget();
            // Distinguish between direct array access (arr[i].field) and
            // struct-field-to-array access (obj.field[i]).
            // For direct array: fieldTarget.receiver names the array var.
            // For struct-to-array: fieldTarget.path is non-empty, describing
            // the field chain leading to the array field.
            if (fieldTarget.getPath() == null || fieldTarget.getPath().isEmpty()) {
                // Direct array access: arr[i] or arr[i].field
                MethodVarTable mTable = this.methodVarTable.get(currMethodName);
                if (mTable == null) {
                    internalError(obj.getLineNum(), "internal error: variable table for method '" + currMethodName + "' was not found");
                    this.currType = unknownType();
                    return;
                }
                String arrName = fieldTarget.getReceiver() instanceof Ast.Expr.Id id ? id.getId() : null;
                if (arrName == null) {
                    semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array",
                            obj.getLineNum(), obj.getSpan(), "unknown array",
                            "the array base could not be resolved", null);
                    this.currType = unknownType();
                    return;
                }
                arrayType = mTable.get(arrName);
                if (arrayType == null) {
                    semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array: " + arrName,
                            obj.getLineNum(), obj.getSpan(), "unknown array",
                            "the name is not declared in the current method scope", null);
                    this.currType = unknownType();
                    return;
                }
            } else {
                // Struct field chain to array: obj.field[i] — resolve the field type first.
                this.visit(fieldTarget);
                arrayType = this.currType;
            }
        } else {
            MethodVarTable mTable = this.methodVarTable.get(currMethodName);
            if (mTable == null) {
                internalError(obj.getLineNum(), "internal error: variable table for method '" + currMethodName + "' was not found");
                this.currType = unknownType();
                return;
            }
            arrayType = mTable.get(obj.getArrayName());
            if (arrayType == null) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array: " + obj.getArrayName(),
                        obj.getLineNum(), obj.getSpan(), "unknown array",
                        "the name is not declared in the current method scope", null);
                this.currType = unknownType();
                return;
            }
        }
        Ast.Type.T elementType = getElementType(arrayType);
        if (elementType == null) {
            String name = obj.getFieldTarget() != null
                    ? String.join(".", obj.getFieldTarget().getPath())
                    : obj.getArrayName();
            error(obj.getLineNum(), String.format("field/variable '%s' is not an array; actual type is %s",
                    name, typeName(arrayType)));
            this.currType = unknownType();
            return;
        }
        this.visit(obj.getIndex());
        if (this.currType.getKind() != TypeKind.INT) {
            typeError(DiagnosticCodes.TYPE_INDEX, "int", typeName(this.currType), expressionName(obj.getIndex()),
                    obj.getIndex().getLineNum(), obj.getIndex().getSpan(), "array index", null);
        }
        obj.setElementType(elementType);
        this.currType = obj.getElementType();
        // Handle optional field chain on array element: arr[i].field
        if (obj.getFieldPath() != null && !obj.getFieldPath().isEmpty()) {
            // For array access, the receiver type is already known (elementType).
            // Avoid calling visit(obj) which would recurse infinitely.
            visitFieldAccessFromType(elementType, obj.getFieldPath(), false, obj.getLineNum(), obj.getSpan());
            // currType was set by visitFieldAccessFromType to the final field type.
        }
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
        Ast.Type.T arrayType;
        // Track whether this is a struct-to-array assignment for later skip logic.
        boolean isStructToArray = false;
        if (obj.getFieldTarget() != null) {
            // For arr[i].field = expr or myPath.points[i] = expr: unwrap the
            // field target receiver chain to find the base variable/array access.
            Ast.Expr.T root = obj.getFieldTarget().getReceiver();
            while (root instanceof Ast.Expr.Field f) {
                root = f.getReceiver();
            }
            MethodVarTable assignTable = this.methodVarTable.get(currMethodName);
            if (root instanceof Ast.Expr.ArrayAccess arrayAccess) {
                // Case: arr[i].field = expr (e.g., myPoint.x = 1)
                // The array access itself has the array name and element type.
                String arrName = arrayAccess.getArrayName();
                if (arrName.isEmpty()) {
                    // This shouldn't normally happen for statement-level, but handle it gracefully
                    error(obj.getLineNum(), "array field assignment requires a named array base");
                    this.currType = unknownType();
                    return;
                }
                boolean isLocal = assignTable != null && assignTable.get(arrName) != null;
                if (!isLocal && resolveConst(arrName) != null) {
                    semanticError(DiagnosticCodes.SEM_CONST_IMMUTABLE,
                            "cannot assign to field of constant '" + arrName + "': constants are immutable",
                            obj.getLineNum(), obj.getSpan(), "immutable constant",
                            "fields of constants cannot be modified", null);
                    return;
                }
                arrayType = assignTable != null ? assignTable.get(arrName) : null;
                if (arrayType == null) {
                    semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array: " + arrName,
                            obj.getLineNum(), obj.getSpan(), "unknown array",
                            "the name is not declared in the current method scope", null);
                    this.currType = unknownType();
                    return;
                }
                this.currMethodLocalVar.remove(arrName);
            } else if (root instanceof Ast.Expr.Id rootId) {
                // Case: myPath.points[i] = expr (struct with array field)
                // OR: v.data[0] = expr (field-of-struct is array)
                boolean isLocal = assignTable != null && assignTable.get(rootId.getId()) != null;
                if (!isLocal && resolveConst(rootId.getId()) != null) {
                    semanticError(DiagnosticCodes.SEM_CONST_IMMUTABLE,
                            "cannot assign to field of constant '" + rootId.getId() + "': constants are immutable",
                            obj.getLineNum(), obj.getSpan(), "immutable constant",
                            "fields of constants cannot be modified", null);
                    return;
                }
                Ast.Type.T rootType = assignTable != null ? assignTable.get(rootId.getId()) : null;
                if (rootType == null) {
                    semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array: " + rootId.getId(),
                            obj.getLineNum(), obj.getSpan(), "unknown array",
                            "the name is not declared in the current method scope", null);
                    this.currType = unknownType();
                    return;
                }
                this.currMethodLocalVar.remove(rootId.getId());
                // Resolve the array type by following the field path from rootType.
                // Two distinct cases:
                //   arr[i].x = expr: rootType is array (e.g. Point[2]), fieldPath
                //     points into the element type — keep arrayType as rootType.
                //   myPath.points[i] = expr: rootType is struct, fieldPath leads
                //     to an array field — walk on rootType to find the array.
                java.util.List<String> fieldPath = obj.getFieldTarget().getPath();
                if (isArrayType(rootType)) {
                    // arr[i].field = expr: rootType is already the array type.
                    // The field path points into the element type, handled later.
                    arrayType = rootType;
                } else {
                    // myPath.points[i] = expr: walk fieldPath on struct to find array.
                    arrayType = resolveArrayTypeThroughFields(rootType, fieldPath,
                            obj.getLineNum(), obj.getSpan());
                    isStructToArray = true;
                }
                if (arrayType == null) {
                    this.currType = unknownType();
                    return;
                }
            } else {
                error(obj.getLineNum(), "array field assignment requires a named array base");
                this.currType = unknownType();
                return;
            }
        } else {
            MethodVarTable mTable = this.methodVarTable.get(currMethodName);
            if (mTable == null) {
                internalError(obj.getLineNum(), "internal error: variable table for method '" + currMethodName + "' was not found");
                this.currType = unknownType();
                return;
            }
            arrayType = mTable.get(obj.getArrayName());
            if (arrayType == null) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE, "undefined array: " + obj.getArrayName(),
                        obj.getLineNum(), obj.getSpan(), "unknown array",
                        "the name is not declared in the current method scope", null);
                this.currType = unknownType();
                return;
            }
        }
        Ast.Type.T elementType = getElementType(arrayType);
        if (elementType == null) {
            String name = obj.getFieldTarget() != null
                    ? obj.getFieldTarget().getReceiver().toString()
                    : obj.getArrayName();
            error(obj.getLineNum(), String.format("field/variable '%s' is not an array; actual type is %s",
                    name, typeName(arrayType)));
            this.currType = unknownType();
            return;
        }
        obj.setElementType(elementType);
        this.visit(obj.getIndex());
        if (this.currType.getKind() != TypeKind.INT) {
            typeError(DiagnosticCodes.TYPE_INDEX, "int", typeName(this.currType), expressionName(obj.getIndex()),
                    obj.getIndex().getLineNum(), obj.getIndex().getSpan(), "array index", null);
        }
        // Resolve the target type: if there is a field path (e.g. arr[i].x),
        // use the field type; otherwise use the element type.
        // For struct-to-array assignments (myPath.points[0] = expr), the path
        // points to the array field itself, not into the element type — skip.
        Ast.Type.T targetType = elementType;
        if (obj.getFieldTarget() != null && obj.getFieldTarget().getPath() != null
                && !obj.getFieldTarget().getPath().isEmpty()
                && !isStructToArray) {
            visitFieldAccessFromType(elementType, obj.getFieldTarget().getPath(), false,
                    obj.getLineNum(), obj.getSpan());
            targetType = this.currType;
            obj.setFieldType(targetType);
        }
        this.visit(obj.getExpr());
        if (!isAssignable(targetType, this.currType, obj.getExpr())) {
            if (!rangeErrorIfNeeded(targetType, this.currType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                    "array element assignment")) {
                if (!shortRangeErrorIfNeeded(targetType, this.currType, obj.getExpr(), obj.getLineNum(), obj.getSpan(),
                        "array element assignment")) {
                    typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(targetType), typeName(this.currType),
                            expressionName(obj.getExpr()), obj.getLineNum(), obj.getSpan(), "array element assignment", null);
                }
            }
        }
    }

    /**
     * Resolves the array type by walking through struct field paths.
     * For example, given rootType=struct Path and path=[points], returns int[].
     * Handles pointer auto-dereference for '->' field access.
     */
    private Ast.Type.T resolveArrayTypeThroughFields(Ast.Type.T rootType, java.util.List<String> path,
                                                      int lineNum, site.ilemon.util.SourceSpan span) {
        if (path == null || path.isEmpty()) {
            return rootType;
        }
        Ast.Type.T currentType = rootType;
        for (int i = 0; i < path.size(); i++) {
            // Auto-dereference pointer for -> access
            if (isPointerType(currentType)) {
                currentType = ((Ast.Type.Pointer) currentType).getPointee();
            }
            if (currentType == null || currentType.getKind() != TypeKind.STRUCT) {
                semanticError(DiagnosticCodes.SEM_GENERAL,
                        "field access '.' requires a struct value, but '" + path.get(0)
                                + "' chain reaches type " + typeName(currentType),
                        lineNum, span, "invalid field access",
                        "field access is only valid on struct values", null);
                return null;
            }
            Ast.StructDecl decl = resolveStruct(((Ast.Type.Struct) currentType).getName());
            if (decl == null) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                        "unknown struct type: " + ((Ast.Type.Struct) currentType).getName(),
                        lineNum, span, "unknown struct", null, null);
                return null;
            }
            Ast.Declare.DeclareSingle field = decl.getField(path.get(i));
            if (field == null) {
                semanticError(DiagnosticCodes.SEM_UNKNOWN_VARIABLE,
                        "struct '" + decl.getName() + "' has no field '" + path.get(i) + "'",
                        lineNum, span, "unknown field",
                        "the field is not declared in the struct", null);
                return null;
            }
            currentType = field.getType();
        }
        return currentType;
    }

    // Get array element type (also supports pointer dereference)
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
        } else if (arrayType instanceof Ast.Type.StructArray structArray) {
            Ast.Type.Struct structType = new Ast.Type.Struct(structArray.getStructName());
            return structType;
        } else if (arrayType instanceof Ast.Type.Pointer) {
            // Pointer dereference: return the pointee type
            return ((Ast.Type.Pointer) arrayType).getPointee();
        }
        return null;
    }

    @Override
    public void visit(Ast.Stmt.VarDecl obj) {
        Ast.Declare.T declaration = obj.getDeclaration();
        if (declaration == null) return;
        if (!(declaration instanceof Ast.Declare.DeclareSingle declareSingle)) {
            return;
        }

        MethodVarTable mTable = this.methodVarTable.get(currMethodName);
        if (mTable == null) {
            internalError(obj.getLineNum(), "internal error: variable table for method '" + currMethodName + "' was not found");
            return;
        }

        Ast.Type.T declType = declareSingle.getType();
        validateStructTypeReference(declType, declareSingle.getLineNum(), declareSingle.getSpan(), "variable declaration");
        validateEnumTypeReference(declType, declareSingle.getLineNum(), declareSingle.getSpan(), "variable declaration");
        validatePointerDeclarations(List.of(declareSingle));

        Ast.Expr.T initExp = declareSingle.getInitExp();
        if (initExp != null) {
            this.visit(initExp);
            Ast.Type.T initType = (initExp instanceof Ast.Expr.Call call)
                    ? call.getReturnType()
                    : this.currType;

            if (initExp instanceof Ast.Expr.InitializerList initList && isArrayType(declType)) {
                validateArrayInitializer(declType, initList, declareSingle.getLineNum(), declareSingle.getSpan());
            } else if (!isAssignable(declType, initType, initExp)) {
                if (!rangeErrorIfNeeded(declType, initType, initExp, declareSingle.getLineNum(),
                        declareSingle.getSpan(), "variable initializer for '" + declareSingle.getId() + "'")
                        && !shortRangeErrorIfNeeded(declType, initType, initExp, declareSingle.getLineNum(),
                        declareSingle.getSpan(), "variable initializer for '" + declareSingle.getId() + "'")) {
                    typeError(DiagnosticCodes.TYPE_ASSIGNMENT, typeName(declType), typeName(initType),
                            expressionName(initExp), declareSingle.getLineNum(), declareSingle.getSpan(),
                            "variable initializer for '" + declareSingle.getId() + "'", null);
                }
            }
            if (isPointerType(declType) && exprMayPointToLocal(initExp)) {
                this.localAddrTaint.add(declareSingle.getId());
            }
            mTable.declare(declareSingle);
        } else {
            mTable.declare(declareSingle);
            if (!isArrayType(declType)) {
                this.currMethodLocalVar.add(declareSingle.getId());
            }
        }
    }

    private void collectVarDeclNodes(List<Ast.Stmt.T> stmts, Set<Ast.Declare.T> decls) {
        if (stmts == null) return;
        for (Ast.Stmt.T s : stmts) {
            collectVarDeclNodes(s, decls);
        }
    }

    private void collectVarDeclNodeIds(List<Ast.Stmt.T> stmts, Set<String> ids) {
        if (stmts == null) return;
        for (Ast.Stmt.T s : stmts) {
            collectVarDeclNodeId(s, ids);
        }
    }

    private void collectVarDeclNodeId(Ast.Stmt.T stmt, Set<String> ids) {
        if (stmt == null) return;
        if (stmt instanceof Ast.Stmt.VarDecl varDecl) {
            Ast.Declare.DeclareSingle dec = (Ast.Declare.DeclareSingle) varDecl.getDeclaration();
            if (dec != null) ids.add(dec.getId());
        } else if (stmt instanceof Ast.Stmt.Block block) {
            collectVarDeclNodeIds(block.getStmts(), ids);
        } else if (stmt instanceof Ast.Stmt.If ifStmt) {
            collectVarDeclNodeId(ifStmt.getThenStmt(), ids);
            collectVarDeclNodeId(ifStmt.getElseStmt(), ids);
        } else if (stmt instanceof Ast.Stmt.While whileStmt) {
            collectVarDeclNodeId(whileStmt.getBody(), ids);
        } else if (stmt instanceof Ast.Stmt.For forStmt) {
            collectVarDeclNodeId(forStmt.getInit(), ids);
            collectVarDeclNodeId(forStmt.getBody(), ids);
        }
    }

    private void collectVarDeclNodes(Ast.Stmt.T stmt, Set<Ast.Declare.T> decls) {
        if (stmt == null) return;
        if (stmt instanceof Ast.Stmt.VarDecl varDecl) {
            decls.add(varDecl.getDeclaration());
        } else if (stmt instanceof Ast.Stmt.Block block) {
            collectVarDeclNodes(block.getStmts(), decls);
        } else if (stmt instanceof Ast.Stmt.If ifStmt) {
            collectVarDeclNodes(ifStmt.getThenStmt(), decls);
            collectVarDeclNodes(ifStmt.getElseStmt(), decls);
        } else if (stmt instanceof Ast.Stmt.While whileStmt) {
            collectVarDeclNodes(whileStmt.getBody(), decls);
        } else if (stmt instanceof Ast.Stmt.For forStmt) {
            collectVarDeclNodes(forStmt.getInit(), decls);
            collectVarDeclNodes(forStmt.getBody(), decls);
        }
    }
    private void checkIncDecOperand(Ast.Expr.T target, int lineNum, site.ilemon.util.SourceSpan span, String op) {
        if (target instanceof Ast.Expr.Id id) {
            MethodVarTable assignTable = this.methodVarTable.get(currMethodName);
            boolean isLocalTarget = assignTable != null && assignTable.get(id.getId()) != null;
            if (!isLocalTarget && resolveConst(id.getId()) != null) {
                semanticError(DiagnosticCodes.SEM_CONST_IMMUTABLE,
                        "cannot assign to constant '" + id.getId() + "': constants are immutable",
                        lineNum, span, "immutable constant",
                        "constants cannot be reassigned after declaration", null);
                return;
            }
        } else if (!(target instanceof Ast.Expr.Field || target instanceof Ast.Expr.ArrayAccess || target instanceof Ast.Expr.Deref)) {
            error(lineNum, op + " requires a modifiable lvalue operand");
        }
    }

    @Override
    public void visit(Ast.Expr.PreInc obj) {
        checkIncDecOperand(obj.getExp(), obj.getLineNum(), obj.getSpan(), "++");
        this.visit(obj.getExp());
        if (!isNumberType(this.currType)) { error(obj.getLineNum(), "++ requires a numeric operand"); }
    }
    @Override
    public void visit(Ast.Expr.PostInc obj) {
        checkIncDecOperand(obj.getExp(), obj.getLineNum(), obj.getSpan(), "++");
        this.visit(obj.getExp());
        if (!isNumberType(this.currType)) { error(obj.getLineNum(), "++ requires a numeric operand"); }
    }
    @Override
    public void visit(Ast.Expr.PreDec obj) {
        checkIncDecOperand(obj.getExp(), obj.getLineNum(), obj.getSpan(), "--");
        this.visit(obj.getExp());
        if (!isNumberType(this.currType)) { error(obj.getLineNum(), "-- requires a numeric operand"); }
    }
    @Override
    public void visit(Ast.Expr.PostDec obj) {
        checkIncDecOperand(obj.getExp(), obj.getLineNum(), obj.getSpan(), "--");
        this.visit(obj.getExp());
        if (!isNumberType(this.currType)) { error(obj.getLineNum(), "-- requires a numeric operand"); }
    }
    @Override
    public void visit(Ast.Expr.UnaryPlus obj) {
        this.visit(obj.getExp());
        if (!isNumberType(this.currType)) { error(obj.getLineNum(), "+ requires a numeric operand"); }
    }
    @Override
    public void visit(Ast.Expr.UnaryMinus obj) {
        this.visit(obj.getExp());
        if (!isNumberType(this.currType)) { error(obj.getLineNum(), "- requires a numeric operand"); }
    }
    @Override
    public void visit(Ast.Expr.BitNot obj) {
        this.visit(obj.getExp());
        if (!isIntegerLike(this.currType)) { error(obj.getLineNum(), "~ requires an integer operand"); }
    }
    @Override
    public void visit(Ast.Expr.Ternary obj) {
        this.visit(obj.getCondition());
        if (this.currType.getKind() != TypeKind.BOOL) { error(obj.getLineNum(), "ternary condition must be bool"); }
        this.visit(obj.getTrueExpr());
        Ast.Type.T tType = this.currType;
        this.visit(obj.getFalseExpr());
        if (!isMatch(tType, this.currType)) {
            Ast.Type.T promoted = promoteNumeric(tType, this.currType);
            if (promoted != null) { this.currType = promoted; }
            else { error(obj.getLineNum(), "ternary branches must have matching types"); }
        }
    }

    @Override
    public void visit(Ast.Stmt.ExprStmt obj) {
        if (obj.getExpr() != null) {
            this.visit(obj.getExpr());
        }
    }
}
