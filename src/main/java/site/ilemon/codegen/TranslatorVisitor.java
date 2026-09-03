package site.ilemon.codegen;


import site.ilemon.ast.Ast.*;
import site.ilemon.ast.Ast.Type.TypeKind;
import site.ilemon.codegen.ast.Ast;
import site.ilemon.codegen.ast.Label;
import site.ilemon.visitor.ISemanticVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import site.ilemon.exception.CompilerException;

/**
 * IR Translation Visitor - translates the frontend AST into Jasmin IL instruction sequences.
 *
 * <p>Traverses frontend AST nodes in {@link site.ilemon.ast.Ast} and generates
 * the corresponding {@link Ast} (backend IR) instruction sequences. During translation:</p>
 * <ul>
 *   <li>Allocates local variable indices for each method ({@code indexTable})</li>
 *   <li>Translates boolean expressions into if-then-else 0/1 assignments</li>
 *   <li>Generates JVM-type-specific load/store/arithmetic instructions</li>
 *   <li>Emits invokestatic instructions for method calls</li>
 * </ul>
 *
 * <p>The translation result is stored in {@link #prog} for {@link ByteCodeGenerator} to write to {@code .il} files.</p>
 *
 * @author andy
 * @see ByteCodeGenerator
 * @see Ast
 */
public class TranslatorVisitor implements ISemanticVisitor {

    @Override
    public void visit(Stmt.Break obj) {
        if (!obj.getBreakList().isEmpty()) {
            emit(new Ast.Stmt.Goto(obj.getBreakList().get(0)));
        }
    }

    @Override
    public void visit(Stmt.Continue obj) {
        if (!obj.getContinueList().isEmpty()) {
            emit(new Ast.Stmt.Goto(obj.getContinueList().get(0)));
        }
    }


    private String classId;
    // Variable index
    private int index;

    // Variable table [key: variable name, value: variable index]
    private HashMap<String, Integer> indexTable;
    private Ast.Type.T type;
    private Ast.Type.T currentMethodReturnType;
    private Ast.Declare.DeclareSingle dec;
    private Ast.Method.MethodSingle method;
    private Ast.MainClass.MainClassSingle mainClass;
    public Ast.Program.ProgramSingle prog;
    private List<Ast.Stmt.T> stmts;
    private HashMap<String, List<Ast.Type.T>> methodFormalTypes;

    public TranslatorVisitor() {
        this.stmts = new ArrayList<>();
        this.classId = null;
        this.indexTable = null;
        this.type = null;
        this.currentMethodReturnType = null;
        this.dec = null;
        this.method = null;
        this.classId = null;
        this.mainClass = null;
        this.prog = null;
        this.methodFormalTypes = new HashMap<>();
    }

    private void emit(Ast.Stmt.T stmt) {
        this.stmts.add(stmt);
    }

    private int emitJump(Ast.Stmt.T stmt) {
        emit(stmt);
        return this.stmts.size() - 1;
    }

    private static class BoolCode {
        final List<Integer> trueList;
        final List<Integer> falseList;

        BoolCode(List<Integer> trueList, List<Integer> falseList) {
            this.trueList = trueList;
            this.falseList = falseList;
        }
    }

    private List<Integer> makelist(int index) {
        List<Integer> list = new ArrayList<>();
        list.add(index);
        return list;
    }

    private List<Integer> merge(List<Integer> left, List<Integer> right) {
        List<Integer> result = new ArrayList<>();
        result.addAll(left);
        result.addAll(right);
        return result;
    }

    private void backpatch(List<Integer> list, Label target) {
        for (Integer index : list) {
            setJumpTarget(this.stmts.get(index), target);
        }
    }

    private void setJumpTarget(Ast.Stmt.T stmt, Label target) {
        if (stmt instanceof Ast.Stmt.Goto) {
            ((Ast.Stmt.Goto) stmt).l = target;
        } else if (stmt instanceof Ast.Stmt.Ifgt) {
            ((Ast.Stmt.Ifgt) stmt).l = target;
        } else if (stmt instanceof Ast.Stmt.Ificmplt) {
            ((Ast.Stmt.Ificmplt) stmt).l = target;
        } else if (stmt instanceof Ast.Stmt.Ificmpgt) {
            ((Ast.Stmt.Ificmpgt) stmt).l = target;
        } else if (stmt instanceof Ast.Stmt.Ificmpge) {
            ((Ast.Stmt.Ificmpge) stmt).l = target;
        } else if (stmt instanceof Ast.Stmt.Ificmple) {
            ((Ast.Stmt.Ificmple) stmt).l = target;
        } else if (stmt instanceof Ast.Stmt.Ificmpeq) {
            ((Ast.Stmt.Ificmpeq) stmt).l = target;
        } else if (stmt instanceof Ast.Stmt.Ificmpne) {
            ((Ast.Stmt.Ificmpne) stmt).l = target;
        } else {
            throw new CompilerException("Cannot backpatch non-branch IR statement: "
                    + stmt.getClass().getName());
        }
    }

    private BoolCode translateCondition(Expr.T expr) {
        if (expr instanceof Expr.And) {
            Expr.And and = (Expr.And) expr;
            BoolCode left = translateCondition(and.getLeft());
            Label rightBegin = new Label();
            emit(new Ast.Stmt.LabelJ(rightBegin));
            backpatch(left.trueList, rightBegin);
            BoolCode right = translateCondition(and.getRight());
            return new BoolCode(right.trueList, merge(left.falseList, right.falseList));
        }
        if (expr instanceof Expr.Or) {
            Expr.Or or = (Expr.Or) expr;
            BoolCode left = translateCondition(or.getLeft());
            Label rightBegin = new Label();
            emit(new Ast.Stmt.LabelJ(rightBegin));
            backpatch(left.falseList, rightBegin);
            BoolCode right = translateCondition(or.getRight());
            return new BoolCode(merge(left.trueList, right.trueList), right.falseList);
        }
        if (expr instanceof Expr.Not) {
            BoolCode inner = translateCondition(((Expr.Not) expr).getExpr());
            return new BoolCode(inner.falseList, inner.trueList);
        }
        if (expr instanceof Expr.True) {
            return new BoolCode(makelist(emitJump(new Ast.Stmt.Goto(null))), new ArrayList<>());
        }
        if (expr instanceof Expr.False) {
            return new BoolCode(new ArrayList<>(), makelist(emitJump(new Ast.Stmt.Goto(null))));
        }
        if (expr instanceof Expr.GT) return translateComparison((Expr.GT) expr, ">");
        if (expr instanceof Expr.LT) return translateComparison((Expr.LT) expr, "<");
        if (expr instanceof Expr.LTE) return translateComparison((Expr.LTE) expr, "<=");
        if (expr instanceof Expr.GTE) return translateComparison((Expr.GTE) expr, ">=");
        if (expr instanceof Expr.EQ) return translateComparison((Expr.EQ) expr, "==");
        if (expr instanceof Expr.NEQ) return translateComparison((Expr.NEQ) expr, "!=");

        this.visit(expr);
        int trueJump = emitJump(new Ast.Stmt.Ifgt(null));
        int falseJump = emitJump(new Ast.Stmt.Goto(null));
        return new BoolCode(makelist(trueJump), makelist(falseJump));
    }

    private BoolCode translateComparison(Expr.GT expr, String op) {
        Ast.Type.T type = emitNumericOperands(expr.getLeft(), expr.getRight());
        return comparisonJumps(op, type);
    }

    private BoolCode translateComparison(Expr.LT expr, String op) {
        Ast.Type.T type = emitNumericOperands(expr.getLeft(), expr.getRight());
        return comparisonJumps(op, type);
    }

    private BoolCode translateComparison(Expr.LTE expr, String op) {
        Ast.Type.T type = emitNumericOperands(expr.getLeft(), expr.getRight());
        return comparisonJumps(op, type);
    }

    private BoolCode translateComparison(Expr.GTE expr, String op) {
        Ast.Type.T type = emitNumericOperands(expr.getLeft(), expr.getRight());
        return comparisonJumps(op, type);
    }

    private BoolCode translateComparison(Expr.EQ expr, String op) {
        Ast.Type.T type = emitNumericOperands(expr.getLeft(), expr.getRight());
        return comparisonJumps(op, type);
    }

    private BoolCode translateComparison(Expr.NEQ expr, String op) {
        Ast.Type.T type = emitNumericOperands(expr.getLeft(), expr.getRight());
        return comparisonJumps(op, type);
    }

    private BoolCode comparisonJumps(String op, Ast.Type.T operandType) {
        if (operandType instanceof Ast.Type.Float) {
            emit(usesCompareGreaterOnNaN(op) ? new Ast.Stmt.Fcmpg() : new Ast.Stmt.Fcmpl());
            emit(new Ast.Stmt.Istore(++index));
            emit(new Ast.Stmt.Iload(index));
            emit(new Ast.Stmt.Ldc(0));
        } else if (operandType instanceof Ast.Type.Double) {
            emit(usesCompareGreaterOnNaN(op) ? new Ast.Stmt.Dcmpg() : new Ast.Stmt.Dcmpl());
            emit(new Ast.Stmt.Istore(++index));
            emit(new Ast.Stmt.Iload(index));
            emit(new Ast.Stmt.Ldc(0));
        } else if (operandType instanceof Ast.Type.Long) {
            emit(new Ast.Stmt.Lcmp());
            emit(new Ast.Stmt.Istore(++index));
            emit(new Ast.Stmt.Iload(index));
            emit(new Ast.Stmt.Ldc(0));
        }

        int trueJump;
        if (">".equals(op)) {
            trueJump = emitJump(new Ast.Stmt.Ificmpgt(null));
        } else if ("<".equals(op)) {
            trueJump = emitJump(new Ast.Stmt.Ificmplt(null));
        } else if (">=".equals(op)) {
            trueJump = emitJump(new Ast.Stmt.Ificmpge(null));
        } else if ("<=".equals(op)) {
            trueJump = emitJump(new Ast.Stmt.Ificmple(null));
        } else if ("==".equals(op)) {
            trueJump = emitJump(new Ast.Stmt.Ificmpeq(null));
        } else if ("!=".equals(op)) {
            trueJump = emitJump(new Ast.Stmt.Ificmpne(null));
        } else {
            throw new CompilerException("Unsupported comparison operator: " + op);
        }
        int falseJump = emitJump(new Ast.Stmt.Goto(null));
        return new BoolCode(makelist(trueJump), makelist(falseJump));
    }

    private boolean usesCompareGreaterOnNaN(String op) {
        return "<".equals(op) || "<=".equals(op);
    }

    private void emitBooleanValue(Expr.T expr) {
        BoolCode code = translateCondition(expr);
        Label trueLabel = new Label();
        Label falseLabel = new Label();
        Label nextLabel = new Label();
        backpatch(code.trueList, trueLabel);
        backpatch(code.falseList, falseLabel);
        emit(new Ast.Stmt.LabelJ(trueLabel));
        emit(new Ast.Stmt.Ldc(1));
        emit(new Ast.Stmt.Goto(nextLabel));
        emit(new Ast.Stmt.LabelJ(falseLabel));
        emit(new Ast.Stmt.Ldc(0));
        emit(new Ast.Stmt.Goto(nextLabel));
        emit(new Ast.Stmt.LabelJ(nextLabel));
        this.type = new Ast.Type.Int();
    }

    private int allocateTemp(Ast.Type.T type) {
        int temp = ++index;
        if (type instanceof Ast.Type.Double || type instanceof Ast.Type.Long) {
            index++;
        }
        return temp;
    }

    private void emitStore(Ast.Type.T type, int localIndex) {
        if (type instanceof Ast.Type.IntArray || type instanceof Ast.Type.ByteArray || type instanceof Ast.Type.FloatArray
                || type instanceof Ast.Type.DoubleArray || type instanceof Ast.Type.BoolArray) emit(new Ast.Stmt.Astore(localIndex));
        else if (type instanceof Ast.Type.Double) emit(new Ast.Stmt.Dstore(localIndex));
        else if (type instanceof Ast.Type.Long) emit(new Ast.Stmt.Lstore(localIndex));
        else if (type instanceof Ast.Type.Float) emit(new Ast.Stmt.Fstore(localIndex));
        else emit(new Ast.Stmt.Istore(localIndex));
    }

    private void emitLoad(Ast.Type.T type, int localIndex) {
        if (type instanceof Ast.Type.IntArray || type instanceof Ast.Type.ByteArray || type instanceof Ast.Type.FloatArray
                || type instanceof Ast.Type.DoubleArray || type instanceof Ast.Type.BoolArray) emit(new Ast.Stmt.Aload(localIndex));
        else if (type instanceof Ast.Type.Double) emit(new Ast.Stmt.Dload(localIndex));
        else if (type instanceof Ast.Type.Long) emit(new Ast.Stmt.Lload(localIndex));
        else if (type instanceof Ast.Type.Float) emit(new Ast.Stmt.Fload(localIndex));
        else emit(new Ast.Stmt.Iload(localIndex));
    }

    private boolean isNumeric(Ast.Type.T type) {
        return type instanceof Ast.Type.Int || type instanceof Ast.Type.Byte || type instanceof Ast.Type.Long
                || type instanceof Ast.Type.Float || type instanceof Ast.Type.Double;
    }

    private Ast.Type.T promotedNumericType(Ast.Type.T left, Ast.Type.T right) {
        if (!isNumeric(left) || !isNumeric(right)) {
            return left;
        }
        if (left instanceof Ast.Type.Double || right instanceof Ast.Type.Double) return new Ast.Type.Double();
        if (left instanceof Ast.Type.Float || right instanceof Ast.Type.Float) return new Ast.Type.Float();
        if (left instanceof Ast.Type.Long || right instanceof Ast.Type.Long) return new Ast.Type.Long();
        return new Ast.Type.Int();
    }

    private void emitConversion(Ast.Type.T from, Ast.Type.T to) {
        if ((from instanceof Ast.Type.Int || from instanceof Ast.Type.Byte) && to instanceof Ast.Type.Float) {
            emit(new Ast.Stmt.I2f());
            this.type = new Ast.Type.Float();
        } else if ((from instanceof Ast.Type.Int || from instanceof Ast.Type.Byte) && to instanceof Ast.Type.Double) {
            emit(new Ast.Stmt.I2d());
            this.type = new Ast.Type.Double();
        } else if ((from instanceof Ast.Type.Int || from instanceof Ast.Type.Byte) && to instanceof Ast.Type.Long) {
            emit(new Ast.Stmt.I2l());
            this.type = new Ast.Type.Long();
        } else if (from instanceof Ast.Type.Long && to instanceof Ast.Type.Float) {
            emit(new Ast.Stmt.L2f());
            this.type = new Ast.Type.Float();
        } else if (from instanceof Ast.Type.Long && to instanceof Ast.Type.Double) {
            emit(new Ast.Stmt.L2d());
            this.type = new Ast.Type.Double();
        } else if (from instanceof Ast.Type.Float && to instanceof Ast.Type.Double) {
            emit(new Ast.Stmt.F2d());
            this.type = new Ast.Type.Double();
        } else {
            this.type = from;
        }
    }

    private void emitWideningConversion(Ast.Type.T expectedType) {
        if (expectedType == null) {
            return;
        }
        emitConversion(this.type, expectedType);
    }

    private Ast.Type.T emitNumericOperands(Expr.T left, Expr.T right) {
        this.visit(left);
        Ast.Type.T leftType = this.type;
        int leftTemp = allocateTemp(leftType);
        emitStore(leftType, leftTemp);
        this.visit(right);
        Ast.Type.T rightType = this.type;
        int rightTemp = allocateTemp(rightType);
        emitStore(rightType, rightTemp);
        Ast.Type.T resultType = promotedNumericType(leftType, rightType);
        emitLoad(leftType, leftTemp);
        emitConversion(leftType, resultType);
        emitLoad(rightType, rightTemp);
        emitConversion(rightType, resultType);
        this.type = resultType;
        return resultType;
    }

    /**
     * Safely look up a variable index from the table, throwing an internal compiler error if not found.
     */
    private int lookupIndex(String id) {
        Integer idx = this.indexTable.get(id);
        if (idx == null) {
            throw new CompilerException(
                "[Code Generation] Internal error: Variable '" + id + "' not registered in index table");
        }
        return idx;
    }

    private String typeName(Ast.Type.T type) {
        if (type == null) {
            return "unknown";
        }
        String name = type.toString();
        return name.startsWith("@") ? name.substring(1) : name;
    }

    private void unsupportedArithmetic(String operator, Ast.Type.T type, int lineNum) {
        throw new CompilerException(String.format(
                "[Code Generation] Line %d: Operator '%s' does not support type %s",
                lineNum, operator, typeName(type)));
    }

    private Ast.Type.T toCodegenType(Type.T type) {
        if (type instanceof Type.Int) return new Ast.Type.Int();
        if (type instanceof Type.Byte) return new Ast.Type.Byte();
        if (type instanceof Type.Long) return new Ast.Type.Long();
        if (type instanceof Type.Float) return new Ast.Type.Float();
        if (type instanceof Type.Double) return new Ast.Type.Double();
        if (type instanceof Type.Bool) return new Ast.Type.Bool();
        if (type instanceof Type.Str) return new Ast.Type.Str();
        if (type instanceof Type.Void) return new Ast.Type.Void();
        if (type instanceof Type.IntArray) return new Ast.Type.IntArray();
        if (type instanceof Type.ByteArray) return new Ast.Type.ByteArray();
        if (type instanceof Type.LongArray) return new Ast.Type.LongArray();
        if (type instanceof Type.FloatArray) return new Ast.Type.FloatArray();
        if (type instanceof Type.DoubleArray) return new Ast.Type.DoubleArray();
        if (type instanceof Type.BoolArray) return new Ast.Type.BoolArray();
        if (type instanceof Type.StringArray) return new Ast.Type.StringArray();
        throw new CompilerException("[Code Generation] Unsupported type: " + type);
    }

    private int localSlots(Ast.Type.T type) {
        return type instanceof Ast.Type.Double || type instanceof Ast.Type.Long ? 2 : 1;
    }

    private boolean isSourceArrayType(Type.T type) {
        return type instanceof Type.IntArray
                || type instanceof Type.ByteArray
                || type instanceof Type.LongArray
                || type instanceof Type.FloatArray
                || type instanceof Type.DoubleArray
                || type instanceof Type.BoolArray
                || type instanceof Type.StringArray;
    }

    private boolean isCodegenArrayType(Ast.Type.T type) {
        return type != null && (type.getKind() == TypeKind.INT_ARRAY
                || type.getKind() == TypeKind.BYTE_ARRAY
                || type.getKind() == TypeKind.LONG_ARRAY
                || type.getKind() == TypeKind.FLOAT_ARRAY
                || type.getKind() == TypeKind.DOUBLE_ARRAY
                || type.getKind() == TypeKind.BOOL_ARRAY
                || type.getKind() == TypeKind.STRING_ARRAY);
    }

    private Ast.Type.T arrayElementType(Type.T type) {
        if (type instanceof Type.IntArray) return new Ast.Type.Int();
        if (type instanceof Type.ByteArray) return new Ast.Type.Byte();
        if (type instanceof Type.LongArray) return new Ast.Type.Long();
        if (type instanceof Type.FloatArray) return new Ast.Type.Float();
        if (type instanceof Type.DoubleArray) return new Ast.Type.Double();
        if (type instanceof Type.BoolArray) return new Ast.Type.Bool();
        if (type instanceof Type.StringArray) return new Ast.Type.Str();
        throw new CompilerException("[Code Generation] Not an array type: " + type);
    }

    private int arraySize(Type.T type) {
        if (type instanceof Type.IntArray) return ((Type.IntArray) type).getSize();
        if (type instanceof Type.ByteArray) return ((Type.ByteArray) type).getSize();
        if (type instanceof Type.LongArray) return ((Type.LongArray) type).getSize();
        if (type instanceof Type.FloatArray) return ((Type.FloatArray) type).getSize();
        if (type instanceof Type.DoubleArray) return ((Type.DoubleArray) type).getSize();
        if (type instanceof Type.BoolArray) return ((Type.BoolArray) type).getSize();
        if (type instanceof Type.StringArray) return ((Type.StringArray) type).getSize();
        throw new CompilerException("[Code Generation] Not an array type: " + type);
    }

    private Ast.Declare.DeclareSingle toCodegenDeclare(Declare.DeclareSingle declareSingle) {
        this.visit(declareSingle.getType());
        this.dec = new Ast.Declare.DeclareSingle(this.type, declareSingle.getId());
        return this.dec;
    }

    private void registerFormal(Declare.DeclareSingle declareSingle) {
        Ast.Declare.DeclareSingle formal = toCodegenDeclare(declareSingle);
        this.indexTable.put(declareSingle.getId(), this.index);
        this.index += localSlots(formal.type);
    }

    private void translateLocalDeclare(Declare.DeclareSingle declareSingle) {
        Ast.Declare.DeclareSingle local = toCodegenDeclare(declareSingle);
        this.indexTable.put(declareSingle.getId(), this.index);
        if (isSourceArrayType(declareSingle.getType())) {
            int size = arraySize(declareSingle.getType());
            if (size <= 0) {
                throw new CompilerException("[Code Generation] Local array size must be a positive integer: " + declareSingle.getId());
            }
            emit(new Ast.Stmt.Ldc(size));
            emit(new Ast.Stmt.Newarray(arrayElementType(declareSingle.getType())));
            emit(new Ast.Stmt.Astore(this.index));
            this.index++;
        } else {
            this.index += localSlots(local.type);
        }
    }

    private void processCallArgument(Expr.T expr, Ast.Type.T expectedType) {
        processExpression(expr);
        emitWideningConversion(expectedType);
    }


    @Override
    public void visit(Expr.T obj) {
        obj.accept(this);
    }

    @Override
    public void visit(Expr.Add obj) {
        Ast.Type.T t = emitNumericOperands(obj.getLeft(), obj.getRight());
        if (t.getKind() == TypeKind.INT) {
            emit(new Ast.Stmt.Iadd());
        } else if (t.getKind() == TypeKind.FLOAT) {
            emit(new Ast.Stmt.Fadd());
        } else if (t.getKind() == TypeKind.DOUBLE) {
            emit(new Ast.Stmt.Dadd());
        } else if (t.getKind() == TypeKind.LONG) {
            emit(new Ast.Stmt.Ladd());
        } else {
            unsupportedArithmetic("+", t, obj.getLineNum());
        }
    }


    @Override
    public void visit(Expr obj) {

    }

    /**
     * E -> E1 and E2
     *  E1.true := newlabel
     *  E1.false := E.false
     *  E2.true := E.true
     *  E2.false := E.false
     *  E.code := E1.code || gen(E1.true ':') ||E2.code
     * @param obj
     */
    @Override
    public void visit(Expr.And obj) {
        emitBooleanValue(obj);
    }

    /**
     * E-> id1 relop id2
     * E.code := gen('if' id1.place relop.op id2.place 'goto' E.true) || gen('goto' E.false)
     * @param obj
     */
    @Override
    public void visit(Expr.GT obj) {
        emitBooleanValue(obj);
    }

    @Override
    public void visit(Expr.LT obj) {
        emitBooleanValue(obj);
    }

    /**
     * E -> E1 or E2
     * E1.true := E.true
     * E1.false := newlabel
     * E2.true := E.true
     * E2.false := E.false
     * E.code := E1.code || gen(E1.false ':') || E2.code
     *
     * @param obj
     */
    @Override
    public void visit(Expr.Or obj) {
        emitBooleanValue(obj);
    }

    // E-> not E1
    // E1.true := E.false
    // E1.false := E.true
    // E.code := E1.code
    @Override
    public void visit(Expr.Not obj) {
        emitBooleanValue(obj);
    }

    @Override
    public void visit(Expr.True obj) {
        emit(new Ast.Stmt.Ldc(1));
        this.type = new Ast.Type.Bool();
    }

    @Override
    public void visit(Expr.False obj) {
        emit(new Ast.Stmt.Ldc(0));
        this.type = new Ast.Type.Bool();
    }

    /**
     * S -> if(E) S1 else S2
     * E.true := newlabel
     * E.false := newlabel
     * S1.next := S.next
     * S2.next := S.next
     * S.code := E.code || gen(E.true':') || S1.code || gen('goto' S.next) || gen(E.false':') || S2.code
     *
     * @param obj
     */
    @Override
    public void visit(Stmt.If obj) {
        Label trueLabel = new Label();
        Label falseLabel = new Label();
        Label nextLabel = new Label();

        BoolCode condition = translateCondition(obj.getCondition());
        backpatch(condition.trueList, trueLabel);
        backpatch(condition.falseList, falseLabel);
        // gen(E.true':')
        emit(new Ast.Stmt.LabelJ(trueLabel));
        // S1.code
        obj.getThenStmt().getBreakList().addAll(obj.getBreakList());
        obj.getThenStmt().getContinueList().addAll(obj.getContinueList());
        this.visit(obj.getThenStmt());

        // goto S.next
        emit(new Ast.Stmt.Goto(nextLabel));
        // gen(E.false':')
        emit(new Ast.Stmt.LabelJ(falseLabel));
        // S2.code (may be null)
        if (obj.getElseStmt() != null) {
            obj.getElseStmt().getBreakList().addAll(obj.getBreakList());
            obj.getElseStmt().getContinueList().addAll(obj.getContinueList());
            this.visit(obj.getElseStmt());
        }
        emit(new Ast.Stmt.Goto(nextLabel));
        emit(new Ast.Stmt.LabelJ(nextLabel));

    }

    @Override
    public void visit(Expr.LTE obj) {
        emitBooleanValue(obj);
    }

    @Override
    public void visit(Expr.GTE obj) {
        emitBooleanValue(obj);
    }

    @Override
    public void visit(Expr.EQ obj) {
        emitBooleanValue(obj);
    }

    @Override
    public void visit(Expr.NEQ obj) {
        emitBooleanValue(obj);
    }

    @Override
    public void visit(Expr.Call obj) {
        this.visit(obj.getReturnType());
        Ast.Type.T returnType = this.type;
        List<Ast.Type.T> at = new ArrayList<>();
        List<Ast.Type.T> expectedTypes = this.methodFormalTypes.get(obj.getName());
        for (int i = 0; i < obj.getInputParams().size(); i++) {
            Expr.T expr = obj.getInputParams().get(i);
            Ast.Type.T expectedType = expectedTypes == null ? null : expectedTypes.get(i);
            processCallArgument(expr, expectedType);
            at.add(expectedType == null ? this.type : expectedType);
        }
        emit(new Ast.Stmt.Invokestatic(obj.getName(), at, returnType));
        this.type = returnType;

    }

    /**
     * Handles the case where an argument in a method call is an expression.
     * @param expr Argument expression
     */
    private void processExpression(Expr.T expr) {
        if( checkWhetherBoolExpression(expr) || ( expr instanceof Expr.Call && ((Expr.Call)expr).getReturnType() instanceof Type.Bool) ){
            emitBooleanValue(expr);
        }else{
            this.visit(expr);
        }
    }

    @Override
    public void visit(Stmt.Assign obj) {
        int index = lookupIndex(obj.getId().getId());

        if( checkWhetherBoolExpression(obj.getExpr()) || ( obj.getExpr() instanceof Expr.Call && ((Expr.Call)obj.getExpr()).getReturnType() instanceof Type.Bool) ){
            emitBooleanValue(obj.getExpr());
        } else if (obj.getId().getType() instanceof Type.Double && obj.getExpr() instanceof Expr.Number) {
            // When a float literal is assigned to a double variable, emit a double constant directly
            Expr.Number num = (Expr.Number) obj.getExpr();
            emit(new Ast.Stmt.Ldc(java.lang.Double.parseDouble(num.getValue().toString())));
            this.type = new Ast.Type.Double();
        } else{
            this.visit(obj.getExpr());
        }
        emitWideningConversion(toCodegenType(obj.getId().getType()));

        // Emit xstore index
        if (obj.getId().getType() instanceof Type.Int || obj.getId().getType() instanceof Type.Byte
                || obj.getId().getType() instanceof Type.Bool)
            emit(new Ast.Stmt.Istore(index));
        else if (obj.getId().getType() instanceof Type.Float)
            emit(new Ast.Stmt.Fstore(index));
        else if (obj.getId().getType() instanceof Type.Double) {
            emit(new Ast.Stmt.Dstore(index));
        } else if (obj.getId().getType() instanceof Type.Long) {
            emit(new Ast.Stmt.Lstore(index));
        }
    }


    @Override
    public void visit(Expr.Id obj) {
        int index = lookupIndex(obj.getId());
        if (obj.getType() instanceof Type.Int || obj.getType() instanceof Type.Byte) {
            this.type = new Ast.Type.Int();
            emit(new Ast.Stmt.Iload(index));
        } else if (obj.getType() instanceof Type.Long) {
            this.type = new Ast.Type.Long();
            emit(new Ast.Stmt.Lload(index));
        } else if (obj.getType() instanceof Type.Float) {
            this.type = new Ast.Type.Float();
            emit(new Ast.Stmt.Fload(index));
        } else if (obj.getType() instanceof Type.Double) {
            this.type = new Ast.Type.Double();
            emit(new Ast.Stmt.Dload(index));
        } else if (obj.getType() instanceof Type.Str) {
            this.type = new Ast.Type.Str();
            emit(new Ast.Stmt.Aload(index));
        } else if (isSourceArrayType(obj.getType())) {
            this.type = toCodegenType(obj.getType());
            emit(new Ast.Stmt.Aload(index));
        }
        // If ID is boolean type
        else if( obj.getType() instanceof Type.Bool ){
            emit(new Ast.Stmt.Iload(lookupIndex(obj.getId())));
            this.type = new Ast.Type.Bool();
        }
    }

    @Override
    public void visit(Expr.Div obj) {
        Ast.Type.T t = emitNumericOperands(obj.getLeft(), obj.getRight());
        if (t.getKind() == TypeKind.INT) {
            emit(new Ast.Stmt.Idiv());
        } else if (t.getKind() == TypeKind.FLOAT) {
            emit(new Ast.Stmt.Fdiv());
        } else if (t.getKind() == TypeKind.DOUBLE) {
            emit(new Ast.Stmt.Ddiv());
        } else if (t.getKind() == TypeKind.LONG) {
            emit(new Ast.Stmt.Ldiv());
        } else {
            unsupportedArithmetic("/", t, obj.getLineNum());
        }
    }

    @Override
    public void visit(Expr.Mod obj) {
        Ast.Type.T t = emitNumericOperands(obj.getLeft(), obj.getRight());
        if (t.getKind() == TypeKind.INT) {
            emit(new Ast.Stmt.Irem());
        } else if (t.getKind() == TypeKind.LONG) {
            emit(new Ast.Stmt.Lrem());
        } else {
            unsupportedArithmetic("%", t, obj.getLineNum());
        }
    }

    @Override
    public void visit(Expr.Mul obj) {
        Ast.Type.T t = emitNumericOperands(obj.getLeft(), obj.getRight());
        if (t.getKind() == TypeKind.INT) {
            emit(new Ast.Stmt.Imul());
        } else if (t.getKind() == TypeKind.FLOAT) {
            emit(new Ast.Stmt.Fmul());
        } else if (t.getKind() == TypeKind.DOUBLE) {
            emit(new Ast.Stmt.Dmul());
        } else if (t.getKind() == TypeKind.LONG) {
            emit(new Ast.Stmt.Lmul());
        } else {
            unsupportedArithmetic("*", t, obj.getLineNum());
        }
    }

    @Override
    public void visit(Expr.Number obj) {
        if (obj.getType() instanceof Type.Int) {
            emit(new Ast.Stmt.Ldc(Integer.parseInt(obj.getValue().toString())));
            this.type = new Ast.Type.Int();
        } else if (obj.getType() instanceof Type.Long) {
            emit(new Ast.Stmt.Ldc(java.lang.Long.parseLong(obj.getValue().toString())));
            this.type = new Ast.Type.Long();
        } else if (obj.getType() instanceof Type.Float) {
            emit(new Ast.Stmt.Ldc(Float.parseFloat(obj.getValue().toString())));
            this.type = new Ast.Type.Float();
        } else if (obj.getType() instanceof Type.Double) {
            emit(new Ast.Stmt.Ldc(java.lang.Double.parseDouble(obj.getValue().toString())));
            this.type = new Ast.Type.Double();
        } else {
            throw new CompilerException("[Code Generation] Line " + obj.getLineNum()
                    + ": Unsupported numeric literal type " + obj.getType());
        }
    }


    @Override
    public void visit(Expr.Sub obj) {
        Ast.Type.T t = emitNumericOperands(obj.getLeft(), obj.getRight());
        if (t.getKind() == TypeKind.INT) {
            emit(new Ast.Stmt.Isub());
        } else if (t.getKind() == TypeKind.FLOAT) {
            emit(new Ast.Stmt.Fsub());
        } else if (t.getKind() == TypeKind.DOUBLE) {
            emit(new Ast.Stmt.Dsub());
        } else if (t.getKind() == TypeKind.LONG) {
            emit(new Ast.Stmt.Lsub());
        } else {
            unsupportedArithmetic("-", t, obj.getLineNum());
        }
    }


    @Override
    public void visit(Expr.Str obj) {
        this.type = new Ast.Type.Str();
        String value = obj.getValue();
        value = value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        emit(new Ast.Stmt.Ldc("\"" + value + "\""));
    }

    @Override
    public void visit(Type.T obj) {
        if (obj != null) {
            obj.accept(this);
        }
    }

    @Override
    public void visit(Type.Bool obj) {
        this.type = new Ast.Type.Bool();
    }

    @Override
    public void visit(Type.Byte obj) {
        this.type = new Ast.Type.Byte();
    }

    @Override
    public void visit(Type.Long obj) {
        this.type = new Ast.Type.Long();
    }

    @Override
    public void visit(Type.Float obj) {
        this.type = new Ast.Type.Float();
    }

    @Override
    public void visit(Type.Double obj) {
        this.type = new Ast.Type.Double();
    }

    @Override
    public void visit(Type.Str obj) {
        this.type = new Ast.Type.Str();
    }

    @Override
    public void visit(Type obj) {

    }

    @Override
    public void visit(Type.Void obj) {
        this.type = new Ast.Type.Void();
    }

    @Override
    public void visit(Type.Int obj) {
        this.type = new Ast.Type.Int();
    }

    @Override
    public void visit(Program.T programSingle) {
        this.visit(((Program.ProgramSingle) programSingle).getMainClass());
        this.prog = new Ast.Program.ProgramSingle(this.mainClass);
    }

    @Override
    public void visit(Declare.T obj) {
        Declare.DeclareSingle declareSingle = ((Declare.DeclareSingle) obj);
        if (this.indexTable == null) {
            toCodegenDeclare(declareSingle);
        } else {
            translateLocalDeclare(declareSingle);
        }
    }


    @Override
    public void visit(MainClass.T obj) {

        MainClass.MainClassSingle mainClassSingle = (MainClass.MainClassSingle) obj;
        this.classId = mainClassSingle.getClassId();
        List<Ast.Method.MethodSingle> methods = new ArrayList<>();
        this.methodFormalTypes = new HashMap<>();
        for (int i = 0; i < mainClassSingle.getMethods().size(); i++) {
            Method.MethodSingle methodSingle = (Method.MethodSingle) mainClassSingle.getMethods().get(i);
            List<Ast.Type.T> formalTypes = new ArrayList<>();
            for (int j = 0; j < methodSingle.getFormals().size(); j++) {
                Declare.DeclareSingle formal = (Declare.DeclareSingle) methodSingle.getFormals().get(j);
                formalTypes.add(toCodegenType(formal.getType()));
            }
            this.methodFormalTypes.put(methodSingle.getId(), formalTypes);
        }
        for (int i = 0; i < mainClassSingle.getMethods().size(); i++) {
            Method.MethodSingle methodSingle = (Method.MethodSingle) mainClassSingle.getMethods().get(i);
            this.visit(methodSingle);
            methods.add(this.method);
        }
        this.mainClass = new Ast.MainClass.MainClassSingle(this.classId, methods);
    }

    @Override
    public void visit(Method.MethodSingle obj) {
        this.index = 0;
        this.indexTable = new HashMap<>();
        this.stmts = new ArrayList<>(); // Initialize stmts
        this.visit(obj.getRetType());
        Ast.Type.T returnType = this.type;
        this.currentMethodReturnType = returnType;

        // Traverse formal parameters
        List<Ast.Declare.DeclareSingle> formals = new ArrayList<>();
        for (int i = 0; i < obj.getFormals().size(); i++) {
            registerFormal((Declare.DeclareSingle) obj.getFormals().get(i));
            formals.add(this.dec);
        }

        // Traverse local variables (generates array initialization code here)
        List<Ast.Declare.DeclareSingle> locals = new ArrayList<>();
        for (int i = 0; i < obj.getLocals().size(); i++) {
            translateLocalDeclare((Declare.DeclareSingle) obj.getLocals().get(i));
            locals.add(this.dec);
        }

        // Traverse statements
        for (int i = 0; i < obj.getStms().size(); i++) {
            this.visit(obj.getStms().get(i));
        }
        //this.visit(obj.retExp);

        this.method = new Ast.Method.MethodSingle(returnType, obj.getId(), this.classId,
                formals, locals, this.stmts, 0, this.index);
    }

    //private


    @Override
    public void visit(Stmt.T obj) {
        obj.accept(this);
    }


    @Override
    public void visit(Stmt.Block obj) {
        for (int i = 0; i < obj.getStmts().size(); i++) {
            Stmt.T stmt = obj.getStmts().get(i);
            stmt.getBreakList().addAll(obj.getBreakList());
            stmt.getContinueList().addAll(obj.getContinueList());
            this.visit(stmt);
        }

    }

    private void emitPrintfString(String text, int lineNum) {
        if (text.length() == 0) {
            return;
        }
        this.visit(new Expr.Str(text, lineNum));
        emit(new Ast.Stmt.Printf(new Ast.Type.Str(), text));
    }

    private void emitPrintfValue(Expr.T expr) {
        this.visit(expr);
        if (this.type instanceof Ast.Type.Int || this.type instanceof Ast.Type.Byte) {
            emit(new Ast.Stmt.Printf(new Ast.Type.Int(), null));
        } else if (this.type instanceof Ast.Type.Long) {
            emit(new Ast.Stmt.Printf(new Ast.Type.Long(), null));
        } else if (this.type instanceof Ast.Type.Float) {
            emit(new Ast.Stmt.Printf(new Ast.Type.Float(), null));
        } else if (this.type instanceof Ast.Type.Double) {
            emit(new Ast.Stmt.Printf(new Ast.Type.Double(), null));
        } else {
            throw new CompilerException("[Code Generation] Printf does not support type " + typeName(this.type));
        }
    }

    @Override
    public void visit(Stmt.Printf obj) {
        String format = obj.getFormat();
        StringBuilder literal = new StringBuilder();
        int argIndex = 0;
        for (int i = 0; i < format.length(); i++) {
            char ch = format.charAt(i);
            if (ch != '%') {
                literal.append(ch);
                continue;
            }
            emitPrintfString(literal.toString(), obj.getLineNum());
            literal.setLength(0);
            if (i + 1 >= format.length()) {
                throw new CompilerException("[Code Generation] Printf format string has % without placeholder");
            }
            char placeholder = format.charAt(++i);
            if (placeholder != 'd' && placeholder != 'f') {
                throw new CompilerException("[Code Generation] Printf does not support placeholder %" + placeholder);
            }
            if (obj.getExprs() == null || argIndex >= obj.getExprs().size()) {
                throw new CompilerException("[Code Generation] Printf argument count insufficient");
            }
            emitPrintfValue(obj.getExprs().get(argIndex++));
        }
        emitPrintfString(literal.toString(), obj.getLineNum());
    }

    @Override
    public void visit(Stmt.PrintLine obj) {
        emit(new Ast.Stmt.Ldc("\"\\n\""));
        emit(new Ast.Stmt.Astore(index));
        emit(new Ast.Stmt.Aload(index));
        emit(new Ast.Stmt.PrintLine());
        index++;
    }

    /**
     * @param expr Expression to check
     * @return true if the expression is a boolean expression
     */
    private boolean checkWhetherBoolExpression(Expr.T expr){
        return expr instanceof Expr.GT || expr instanceof Expr.LT
                || expr instanceof Expr.LTE || expr instanceof Expr.GTE
                || expr instanceof Expr.EQ || expr instanceof Expr.NEQ
                || expr instanceof Expr.Not || expr instanceof Expr.And
                || expr instanceof Expr.Or || expr instanceof Expr.True
                || expr instanceof Expr.False;
    }

    @Override
    public void visit(Stmt.Return obj) {
        if ( checkWhetherBoolExpression(obj.getExpr()) ||  ( obj.getExpr() instanceof Expr.Call && ((Expr.Call)obj.getExpr()).getReturnType() instanceof Type.Bool)) {
            emitBooleanValue(obj.getExpr());
        } else {
            this.visit(obj.getExpr());
        }
        emitWideningConversion(this.currentMethodReturnType);
        if (this.type.getKind() == TypeKind.INT || this.type.getKind() == TypeKind.BYTE
                || this.type.getKind() == TypeKind.BOOL)
            emit(new Ast.Stmt.Ireturn());
        else if (this.type.getKind() == TypeKind.LONG)
            emit(new Ast.Stmt.Lreturn());
        else if (this.type.getKind() == TypeKind.FLOAT)
            emit(new Ast.Stmt.Freturn());
        else if (this.type.getKind() == TypeKind.DOUBLE)
            emit(new Ast.Stmt.Dreturn());
        else if (this.type.getKind() == TypeKind.STRING || isCodegenArrayType(this.type))
            emit(new Ast.Stmt.Areturn());

    }

    /**
     *  S -> while(E) do S1
     *      S.begin := newlabel
     *      E.true := newlabel
     *      E.false := S.next
     *      S1.next := S.begin
     *      S.code := gen(S.begin':') || E.code ||gen(E.true':')|| S1.code || gen('goto' S.begin)
     * @param obj
     */
    @Override
    public void visit(Stmt.While obj) {

        //S.begin := newlabel
        Label begin = new Label();

        Label trueLabel = new Label();

        Label next = new Label();

        // gen(S.begin':')
        emit(new Ast.Stmt.LabelJ(begin));

        BoolCode condition = translateCondition(obj.getCondition());
        backpatch(condition.trueList, trueLabel);
        backpatch(condition.falseList, next);

        // gen(E.true':')
        emit(new Ast.Stmt.LabelJ(trueLabel));

        // S1.code
        obj.getBody().getBreakList().addToHead(next);
        obj.getBody().getContinueList().addToHead(begin);
        this.visit(obj.getBody());

        // gen('goto' S.begin)
        emit(new Ast.Stmt.Goto(begin));

        // gen(S.next ':')
        emit(new Ast.Stmt.LabelJ(next));
    }

    @Override
    public void visit(Stmt.For obj) {
        if (obj.getInit() != null) {
            this.visit(obj.getInit());
        }
        Label begin = new Label();
        Label trueLabel = new Label();
        Label updateLabel = new Label();
        Label next = new Label();

        emit(new Ast.Stmt.LabelJ(begin));
        BoolCode condition = translateCondition(obj.getCondition());
        backpatch(condition.trueList, trueLabel);
        backpatch(condition.falseList, next);

        emit(new Ast.Stmt.LabelJ(trueLabel));
        obj.getBody().getBreakList().addToHead(next);
        obj.getBody().getContinueList().addToHead(updateLabel);
        this.visit(obj.getBody());

        emit(new Ast.Stmt.LabelJ(updateLabel));
        if (obj.getUpdate() != null) {
            this.visit(obj.getUpdate());
        }
        emit(new Ast.Stmt.Goto(begin));
        emit(new Ast.Stmt.LabelJ(next));
    }

    @Override
    public void visit(Stmt.Call obj) {
        this.visit(obj.getReturnType());
        Ast.Type.T returnType = this.type;
        List<Ast.Type.T> at = new ArrayList<>();
        List<Ast.Type.T> expectedTypes = this.methodFormalTypes.get(obj.getName());
        for (int i = 0; i < obj.getInputParams().size(); i++) {
            Ast.Type.T expectedType = expectedTypes == null ? null : expectedTypes.get(i);
            processCallArgument(obj.getInputParams().get(i), expectedType);
            at.add(expectedType == null ? this.type : expectedType);
        }
        emit(new Ast.Stmt.Invokestatic(obj.getName(), at, returnType));
        this.type = returnType;
        if (returnType.getKind() == TypeKind.DOUBLE || returnType.getKind() == TypeKind.LONG) {
            emit(new Ast.Stmt.Pop2());
        } else if (returnType.getKind() != TypeKind.VOID) {
            emit(new Ast.Stmt.Pop());
        }
    }

    // ========== Array-related visit methods ==========

    @Override
    public void visit(Type.IntArray obj) {
        this.type = new Ast.Type.IntArray();
    }

    @Override
    public void visit(Type.ByteArray obj) {
        this.type = new Ast.Type.ByteArray();
    }

    @Override
    public void visit(Type.LongArray obj) {
        this.type = new Ast.Type.LongArray();
    }

    @Override
    public void visit(Type.FloatArray obj) {
        this.type = new Ast.Type.FloatArray();
    }

    @Override
    public void visit(Type.DoubleArray obj) {
        this.type = new Ast.Type.DoubleArray();
    }

    @Override
    public void visit(Type.BoolArray obj) {
        this.type = new Ast.Type.BoolArray();
    }

    @Override
    public void visit(Type.StringArray obj) {
        this.type = new Ast.Type.StringArray();
    }

    @Override
    public void visit(Expr.ArrayAccess obj) {
        // Load array reference
        int arrayIndex = lookupIndex(obj.getArrayName());
        emit(new Ast.Stmt.Aload(arrayIndex));
        // Compute array index
        this.visit(obj.getIndex());
        // Emit load instruction based on element type
        if (obj.getElementType() instanceof Type.Int) {
            emit(new Ast.Stmt.Iaload());
            this.type = new Ast.Type.Int();
        } else if (obj.getElementType() instanceof Type.Byte) {
            emit(new Ast.Stmt.Baload());
            this.type = new Ast.Type.Byte();
        } else if (obj.getElementType() instanceof Type.Long) {
            emit(new Ast.Stmt.Laload());
            this.type = new Ast.Type.Long();
        } else if (obj.getElementType() instanceof Type.Float) {
            emit(new Ast.Stmt.Faload());
            this.type = new Ast.Type.Float();
        } else if (obj.getElementType() instanceof Type.Double) {
            emit(new Ast.Stmt.Daload());
            this.type = new Ast.Type.Double();
        } else if (obj.getElementType() instanceof Type.Bool) {
            emit(new Ast.Stmt.Baload());
            this.type = new Ast.Type.Int(); // bool is represented as int in the JVM
        } else if (obj.getElementType() instanceof Type.Str) {
            emit(new Ast.Stmt.Aaload());
            this.type = new Ast.Type.Str();
        }
    }

    @Override
    public void visit(Expr.ArrayLength obj) {
        int arrayIndex = lookupIndex(obj.getArrayName());
        emit(new Ast.Stmt.Aload(arrayIndex));
        emit(new Ast.Stmt.Arraylength());
        this.type = new Ast.Type.Int();
    }

    @Override
    public void visit(Stmt.ArrayAssign obj) {
        // Load array reference
        int arrayIndex = lookupIndex(obj.getArrayName());
        emit(new Ast.Stmt.Aload(arrayIndex));
        // Compute array index
        this.visit(obj.getIndex());
        // Compute value
        this.visit(obj.getExpr());
        // Emit store instruction based on element type
        if (obj.getElementType() instanceof Type.Int) {
            emit(new Ast.Stmt.Iastore());
        } else if (obj.getElementType() instanceof Type.Byte) {
            emit(new Ast.Stmt.Bastore());
        } else if (obj.getElementType() instanceof Type.Long) {
            emitWideningConversion(new Ast.Type.Long());
            emit(new Ast.Stmt.Lastore());
        } else if (obj.getElementType() instanceof Type.Float) {
            emitWideningConversion(new Ast.Type.Float());
            emit(new Ast.Stmt.Fastore());
        } else if (obj.getElementType() instanceof Type.Double) {
            emitWideningConversion(new Ast.Type.Double());
            emit(new Ast.Stmt.Dastore());
        } else if (obj.getElementType() instanceof Type.Bool) {
            emit(new Ast.Stmt.Bastore());
        } else if (obj.getElementType() instanceof Type.Str) {
            emit(new Ast.Stmt.Aastore());
        }
    }
}
