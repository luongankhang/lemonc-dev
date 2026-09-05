package site.ilemon.ast;

import site.ilemon.visitor.ISemanticVisitor;
import site.ilemon.util.SourceSpan;

import java.util.ArrayList;

/**
 * Created by andy on 2019/7/31.
 */
public class Ast {

    public enum Visibility { PUBLIC, PRIVATE }

    public static class ImportDecl {
        private final String name;
        private final String path;
        private final SourceSpan span;

        public ImportDecl(String name, String path, SourceSpan span) {
            this.name = name;
            this.path = path;
            this.span = span;
        }

        public String getName() { return name; }
        public String getPath() { return path; }
        public SourceSpan getSpan() { return span; }
    }

    /**
     * Program
     */
    public static class Program{
        public static abstract class T{
            private SourceSpan span;
            public SourceSpan getSpan() { return this.span; }
            public void setSpan(SourceSpan span) { this.span = span; }
            public abstract void accept(ISemanticVisitor v);
        }
        public static class ProgramSingle extends T{
            private MainClass.T mainClass;
            public MainClass.T getMainClass() { return this.mainClass; }
            public void setMainClass(MainClass.T mainClass) { this.mainClass = mainClass; }

            public ProgramSingle(MainClass.T mainClass) {
                this.mainClass = mainClass;
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }
    }

    /**
     * MainClass
     */
    public static class MainClass{
        public static abstract class T{
            private SourceSpan span;
            public SourceSpan getSpan() { return this.span; }
            public void setSpan(SourceSpan span) { this.span = span; }
            public abstract void accept(ISemanticVisitor v);
        }
        public static class MainClassSingle extends T {
            private String classId;
            public String getClassId() { return this.classId; }
            public void setClassId(String classId) { this.classId = classId; }
            private ArrayList<Ast.Declare.T> fields;
            public ArrayList<Ast.Declare.T> getFields() { return this.fields; }
            public void setFields(ArrayList<Ast.Declare.T> fields) { this.fields = fields; }
            private ArrayList<Ast.Method.T> methods;
            private ArrayList<ImportDecl> imports = new ArrayList<>();
            public ArrayList<Ast.Method.T> getMethods() { return this.methods; }
            public void setMethods(ArrayList<Ast.Method.T> methods) { this.methods = methods; }
            public ArrayList<ImportDecl> getImports() { return this.imports; }

            public MainClassSingle(String classId, ArrayList<Declare.T> fields, ArrayList<Ast.Method.T> methods) {
                this.classId = classId;
                this.fields = null;
                this.methods = methods;
            }

            public MainClassSingle(String classId, ArrayList<Ast.Method.T> methods) {
                this(classId, null, methods);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }
    }

    /**
     * Stmt
     */
    public static class Stmt{
        public static abstract class T{
            private int lineNum;
            public int getLineNum() { return this.lineNum; }
            public void setLineNum(int lineNum) { this.lineNum = lineNum; }
            private SourceSpan span;
            public SourceSpan getSpan() { return this.span; }
            public void setSpan(SourceSpan span) { this.span = span; }
            public abstract void accept(ISemanticVisitor v);
        }

        public static class Break extends T {
            public Break(int lineNum) { this.setLineNum(lineNum); }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public static class Continue extends T {
            public Continue(int lineNum) { this.setLineNum(lineNum); }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }
        public static class Assign extends T {
            private Ast.Expr.Id id;
            public Ast.Expr.Id getId() { return this.id; }
            public void setId(Ast.Expr.Id id) { this.id = id; }
            private Expr.T expr;
            public Expr.T getExpr() { return this.expr; }
            public void setExpr(Expr.T expr) { this.expr = expr; }
            //private Type.T type;
            public Assign(Ast.Expr.Id id, Expr.T exp, int lineNum) {
                this.id = id;
                this.expr = exp;
                //this.type = type;
                this.setLineNum(lineNum);
            }

            // Constructor with SourceSpan
            public Assign(Ast.Expr.Id id, Expr.T exp, site.ilemon.util.SourceSpan span) {
                this.id = id;
                this.expr = exp;
                this.setSpan(span);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Block extends T{
            private ArrayList<T> stmts;
            public ArrayList<T> getStmts() { return this.stmts; }
            public void setStmts(ArrayList<T> stmts) { this.stmts = stmts; }

            public Block(ArrayList<T> stmts,int lineNum) {
                this.stmts = stmts;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Call extends T {
            /** Method return type **/
            private Ast.Type.T returnType;
            public Ast.Type.T getReturnType() { return this.returnType; }
            public void setReturnType(Ast.Type.T returnType) { this.returnType = returnType; }

            /** Method name **/
            private String name;
            public String getName() { return this.name; }
            public void setName(String name) { this.name = name; }

            /** Method parameters **/
            private ArrayList<Expr.T> inputParams;
            public ArrayList<Expr.T> getInputParams() { return this.inputParams; }
            public void setInputParams(ArrayList<Expr.T> inputParams) { this.inputParams = inputParams; }

            public Call(String name, ArrayList<Expr.T> inputParams,int lineNumber) {
                this.name = name;
                this.inputParams = inputParams;
                this.setLineNum(lineNumber);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class If extends T{
            private Expr.T condition;
            public Expr.T getCondition() { return this.condition; }
            public void setCondition(Expr.T condition) { this.condition = condition; }
            private T thenStmt;
            private T elseStmt;
            public T getThenStmt() { return this.thenStmt; }
            public void setThenStmt(T thenStmt) { this.thenStmt = thenStmt; }
            public T getElseStmt() { return this.elseStmt; }
            public void setElseStmt(T elseStmt) { this.elseStmt = elseStmt; }

            public If(Expr.T condition,T thenStmt,T elseStmt,int lineNum) {
                this.condition = condition;
                this.thenStmt = thenStmt;
                this.elseStmt = elseStmt;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Printf extends T {
            private String format;
            public String getFormat() { return this.format; }
            public void setFormat(String format) { this.format = format; }
            private ArrayList<Ast.Expr.T> exprs;
            public ArrayList<Ast.Expr.T> getExprs() { return this.exprs; }
            public void setExprs(ArrayList<Ast.Expr.T> exprs) { this.exprs = exprs; }

            public Printf(String format,ArrayList<Ast.Expr.T> exprs, int lineNum) {
                this.format = format;
                this.exprs = exprs;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Return extends T {
            private Ast.Expr.T expr;
            public Ast.Expr.T getExpr() { return this.expr; }
            public void setExpr(Ast.Expr.T expr) { this.expr = expr; }

            public Return(Expr.T expr, int lineNum) {
                this.expr = expr;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class While extends T {
            private Ast.Expr.T condition;
            public Ast.Expr.T getCondition() { return this.condition; }
            public void setCondition(Ast.Expr.T condition) { this.condition = condition; }
            private Ast.Stmt.T body;
            public Ast.Stmt.T getBody() { return this.body; }
            public void setBody(Ast.Stmt.T body) { this.body = body; }

            public While(Ast.Expr.T condition, Ast.Stmt.T body, int lineNum)
            {
                this.condition = condition;
                this.body = body;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class For extends T {
            private Ast.Stmt.T init;
            public Ast.Stmt.T getInit() { return this.init; }
            private Ast.Expr.T condition;
            public Ast.Expr.T getCondition() { return this.condition; }
            private Ast.Stmt.T update;
            public Ast.Stmt.T getUpdate() { return this.update; }
            private Ast.Stmt.T body;
            public Ast.Stmt.T getBody() { return this.body; }

            public For(Ast.Stmt.T init, Ast.Expr.T condition, Ast.Stmt.T update,
                       Ast.Stmt.T body, int lineNum) {
                this.init = init;
                this.condition = condition;
                this.update = update;
                this.body = body;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class PrintLine extends T {
            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        // Array assignment statement: arr[index] = expr;
        public static class ArrayAssign extends T {
            private String arrayName;
            public String getArrayName() { return this.arrayName; }
            public void setArrayName(String arrayName) { this.arrayName = arrayName; }
            private Expr.T index;
            public Expr.T getIndex() { return this.index; }
            public void setIndex(Expr.T index) { this.index = index; }
            private Expr.T expr;
            public Expr.T getExpr() { return this.expr; }
            public void setExpr(Expr.T expr) { this.expr = expr; }
            private Type.T elementType;
            public Type.T getElementType() { return this.elementType; }
            public void setElementType(Type.T elementType) { this.elementType = elementType; }

            public ArrayAssign(String arrayName, Expr.T index, Expr.T expr, int lineNum) {
                this.arrayName = arrayName;
                this.index = index;
                this.expr = expr;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Import extends T {
            private final ImportDecl declaration;

            public Import(ImportDecl declaration) {
                this.declaration = declaration;
                if (declaration.getSpan() != null) setSpan(declaration.getSpan());
            }

            public ImportDecl getDeclaration() { return declaration; }

            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }
    }

    /**
     * Declare
     */
    public static class Declare{
        public static abstract class T{
            private int lineNum;
            public int getLineNum() { return this.lineNum; }
            public void setLineNum(int lineNum) { this.lineNum = lineNum; }
            private SourceSpan span;
            public SourceSpan getSpan() { return this.span; }
            public void setSpan(SourceSpan span) { this.span = span; }
            public abstract void accept(ISemanticVisitor v);
        }
        public static class DeclareSingle extends T {
            private Visibility visibility = Visibility.PRIVATE;
            public Visibility getVisibility() { return visibility; }
            public void setVisibility(Visibility visibility) { this.visibility = visibility; }
            private Type.T type;
            public Type.T getType() { return this.type; }
            public void setType(Type.T type) { this.type = type; }
            private String id;
            public String getId() { return this.id; }
            public void setId(String id) { this.id = id; }

            public DeclareSingle(Type.T type, String id,int lineNum) {
                this.type = type;
                this.id = id;
                this.setLineNum(lineNum);
            }

            public DeclareSingle(Type.T type, String id) {
                this.type = type;
                this.id = id;
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }
    }

    /**
     * Type
     */
    public static class Type{
        /**
         * Type enum for type-safe comparisons (replacing toString().equals() checks).
         */
        public enum TypeKind {
            INT, FLOAT, DOUBLE, BOOL, CHAR, BYTE, SHORT, LONG, STRING, VOID,
            INT_ARRAY, FLOAT_ARRAY, DOUBLE_ARRAY, BOOL_ARRAY, STRING_ARRAY, BYTE_ARRAY, SHORT_ARRAY, CHAR_ARRAY, LONG_ARRAY
        }

        public sealed abstract static class T{
            public abstract void accept(ISemanticVisitor v);
            public abstract TypeKind getKind();
        }
        
        public non-sealed static class Void extends T {
            @Override
            public TypeKind getKind() { return TypeKind.VOID; }
            @Override
            public String toString() {
                return "@void";
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }
        public non-sealed static class Int extends T {
            @Override
            public TypeKind getKind() { return TypeKind.INT; }
            @Override
            public String toString() {
                return "@int";
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public non-sealed static class Float extends T {
            @Override
            public TypeKind getKind() { return TypeKind.FLOAT; }
            @Override
            public String toString() {
                return "@float";
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public non-sealed static class Double extends T {
            @Override
            public TypeKind getKind() { return TypeKind.DOUBLE; }
            @Override
            public String toString() {
                return "@double";
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public non-sealed static class Str extends T {
            @Override
            public TypeKind getKind() { return TypeKind.STRING; }
            @Override
            public String toString() {
                return "@string";
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public non-sealed static class Bool extends T {
            @Override
            public TypeKind getKind() { return TypeKind.BOOL; }
            @Override
            public String toString() {
                return "@bool";
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public non-sealed static class Char extends T {
            @Override public TypeKind getKind() { return TypeKind.CHAR; }
            @Override public String toString() { return "@char"; }
            @Override public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class Byte extends T {
            @Override
            public TypeKind getKind() { return TypeKind.BYTE; }
            @Override
            public String toString() { return "@byte"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class Short extends T {
            @Override
            public TypeKind getKind() { return TypeKind.SHORT; }
            @Override
            public String toString() { return "@short"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class Long extends T {
            @Override
            public TypeKind getKind() { return TypeKind.LONG; }
            @Override
            public String toString() { return "@long"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        // Array types
        public non-sealed static class IntArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public IntArray() { this.size = -1; }
            public IntArray(int size) { this.size = size; }
            @Override
            public TypeKind getKind() { return TypeKind.INT_ARRAY; }
            @Override
            public String toString() { return "@int[]"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class ByteArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public ByteArray() { this.size = -1; }
            public ByteArray(int size) { this.size = size; }
            @Override
            public TypeKind getKind() { return TypeKind.BYTE_ARRAY; }
            @Override
            public String toString() { return "@byte[]"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class ShortArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public ShortArray() { this.size = -1; }
            public ShortArray(int size) { this.size = size; }
            @Override
            public TypeKind getKind() { return TypeKind.SHORT_ARRAY; }
            @Override
            public String toString() { return "@short[]"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class CharArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public CharArray() { this.size = -1; }
            public CharArray(int size) { this.size = size; }
            @Override public TypeKind getKind() { return TypeKind.CHAR_ARRAY; }
            @Override public String toString() { return "@char[]"; }
            @Override public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class LongArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public LongArray() { this.size = -1; }
            public LongArray(int size) { this.size = size; }
            @Override
            public TypeKind getKind() { return TypeKind.LONG_ARRAY; }
            @Override
            public String toString() { return "@long[]"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class FloatArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public FloatArray() { this.size = -1; }
            public FloatArray(int size) { this.size = size; }
            @Override
            public TypeKind getKind() { return TypeKind.FLOAT_ARRAY; }
            @Override
            public String toString() { return "@float[]"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class DoubleArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public DoubleArray() { this.size = -1; }
            public DoubleArray(int size) { this.size = size; }
            @Override
            public TypeKind getKind() { return TypeKind.DOUBLE_ARRAY; }
            @Override
            public String toString() { return "@double[]"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class BoolArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public BoolArray() { this.size = -1; }
            public BoolArray(int size) { this.size = size; }
            @Override
            public TypeKind getKind() { return TypeKind.BOOL_ARRAY; }
            @Override
            public String toString() { return "@bool[]"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }

        public non-sealed static class StringArray extends T {
            private int size;
            public int getSize() { return this.size; }
            public void setSize(int size) { this.size = size; }
            public StringArray() { this.size = -1; }
            public StringArray(int size) { this.size = size; }
            @Override
            public TypeKind getKind() { return TypeKind.STRING_ARRAY; }
            @Override
            public String toString() { return "@string[]"; }
            @Override
            public void accept(ISemanticVisitor v) { v.visit(this); }
        }
    }


    /**
     * Expression
     */
    public static class Expr {
        public static abstract class T {
            private int lineNum;
            public int getLineNum() { return this.lineNum; }
            public void setLineNum(int lineNum) { this.lineNum = lineNum; }
            // SourceSpan metadata
            private site.ilemon.util.SourceSpan span;
            public site.ilemon.util.SourceSpan getSpan() { return this.span; }
            public void setSpan(site.ilemon.util.SourceSpan span) { this.span = span; }

            public abstract void accept(ISemanticVisitor v);
        }

        /** Arithmetic expressions **/
        public static class Add extends T{
            private Ast.Expr.T left;
            private Ast.Expr.T right;
            public Ast.Expr.T getLeft() { return this.left; }
            public void setLeft(Ast.Expr.T left) { this.left = left; }
            public Ast.Expr.T getRight() { return this.right; }
            public void setRight(Ast.Expr.T right) { this.right = right; }

            public Add(Ast.Expr.T left, Ast.Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            // Constructor with SourceSpan
            public Add(Ast.Expr.T left, Ast.Expr.T right, site.ilemon.util.SourceSpan span) {
                this.setLeft(left);
                this.setRight(right);
                this.setSpan(span);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Sub extends T{
            private Ast.Expr.T left;
            private Ast.Expr.T right;
            public Ast.Expr.T getLeft() { return this.left; }
            public void setLeft(Ast.Expr.T left) { this.left = left; }
            public Ast.Expr.T getRight() { return this.right; }
            public void setRight(Ast.Expr.T right) { this.right = right; }

            public Sub(Ast.Expr.T left, Ast.Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Mul extends T{
            private Expr.T left;
            private Expr.T right;
            public Expr.T getLeft() { return this.left; }
            public void setLeft(Expr.T left) { this.left = left; }
            public Expr.T getRight() { return this.right; }
            public void setRight(Expr.T right) { this.right = right; }

            public Mul(Expr.T left, Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Div extends T{
            private Expr.T left;
            private Expr.T right;
            public Expr.T getLeft() { return this.left; }
            public void setLeft(Expr.T left) { this.left = left; }
            public Expr.T getRight() { return this.right; }
            public void setRight(Expr.T right) { this.right = right; }

            public Div(Expr.T left, Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Mod extends T{
            private Expr.T left;
            private Expr.T right;
            public Expr.T getLeft() { return this.left; }
            public void setLeft(Expr.T left) { this.left = left; }
            public Expr.T getRight() { return this.right; }
            public void setRight(Expr.T right) { this.right = right; }

            public Mod(Expr.T left, Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        /** Logical expressions **/
        public static class And extends T{
            private Expr.T left;
            private Expr.T right;
            public Expr.T getLeft() { return this.left; }
            public void setLeft(Expr.T left) { this.left = left; }
            public Expr.T getRight() { return this.right; }
            public void setRight(Expr.T right) { this.right = right; }
            public And(Expr.T left, Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Or extends T{
            private Expr.T left;
            private Expr.T right;
            public Expr.T getLeft() { return this.left; }
            public void setLeft(Expr.T left) { this.left = left; }
            public Expr.T getRight() { return this.right; }
            public void setRight(Expr.T right) { this.right = right; }

            public Or(Expr.T left, Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Not extends T{
            private Expr.T expr;
            public Expr.T getExpr() { return this.expr; }
            public void setExpr(Expr.T expr) { this.expr = expr; }

            public Not(Expr.T expr) {
                this.expr = expr;
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Call extends T{
            /** Method return type **/
            private Ast.Type.T returnType;
            public Ast.Type.T getReturnType() { return this.returnType; }
            public void setReturnType(Ast.Type.T returnType) { this.returnType = returnType; }

            /** Method name **/
            private String name;
            public String getName() { return this.name; }
            public void setName(String name) { this.name = name; }

            /** Method parameters **/
            private ArrayList<Expr.T> inputParams;
            public ArrayList<Expr.T> getInputParams() { return this.inputParams; }
            public void setInputParams(ArrayList<Expr.T> inputParams) { this.inputParams = inputParams; }

            public Call(String name, ArrayList<Expr.T> inputParams,int lineNumber) {
                this.name = name;
                this.inputParams = inputParams;
                this.setLineNum(lineNumber);
            }

            public Call(String name, ArrayList<Expr.T> inputParams,int lineNumber,Ast.Type.T rt) {
                this.name = name;
                this.inputParams = inputParams;
                this.setLineNum(lineNumber);
                this.returnType = rt;
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        /** Comparison expressions **/
        // >
        public static class GT extends T{
            private Ast.Expr.T left;
            private Ast.Expr.T right;
            public Ast.Expr.T getLeft() { return this.left; }
            public void setLeft(Ast.Expr.T left) { this.left = left; }
            public Ast.Expr.T getRight() { return this.right; }
            public void setRight(Ast.Expr.T right) { this.right = right; }

            public GT(Ast.Expr.T left, Ast.Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        // <
        public static class LT extends T{
            private Ast.Expr.T left;
            private Ast.Expr.T right;
            public Ast.Expr.T getLeft() { return this.left; }
            public void setLeft(Ast.Expr.T left) { this.left = left; }
            public Ast.Expr.T getRight() { return this.right; }
            public void setRight(Ast.Expr.T right) { this.right = right; }

            public LT(Ast.Expr.T left, Ast.Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        // >=
        public static class GTE extends T{
            private Ast.Expr.T left;
            private Ast.Expr.T right;
            public Ast.Expr.T getLeft() { return this.left; }
            public void setLeft(Ast.Expr.T left) { this.left = left; }
            public Ast.Expr.T getRight() { return this.right; }
            public void setRight(Ast.Expr.T right) { this.right = right; }

            public GTE(Ast.Expr.T left, Ast.Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        // <=
        public static class LTE extends T{
            private Ast.Expr.T left;
            private Ast.Expr.T right;
            public Ast.Expr.T getLeft() { return this.left; }
            public void setLeft(Ast.Expr.T left) { this.left = left; }
            public Ast.Expr.T getRight() { return this.right; }
            public void setRight(Ast.Expr.T right) { this.right = right; }

            public LTE(Ast.Expr.T left, Ast.Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        // ==
        public static class EQ extends T{
            private Ast.Expr.T left;
            private Ast.Expr.T right;
            public Ast.Expr.T getLeft() { return this.left; }
            public void setLeft(Ast.Expr.T left) { this.left = left; }
            public Ast.Expr.T getRight() { return this.right; }
            public void setRight(Ast.Expr.T right) { this.right = right; }

            public EQ(Ast.Expr.T left, Ast.Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        // !=
        public static class NEQ extends T{
            private Ast.Expr.T left;
            private Ast.Expr.T right;
            public Ast.Expr.T getLeft() { return this.left; }
            public void setLeft(Ast.Expr.T left) { this.left = left; }
            public Ast.Expr.T getRight() { return this.right; }
            public void setRight(Ast.Expr.T right) { this.right = right; }

            public NEQ(Ast.Expr.T left, Ast.Expr.T right,int lineNum) {
                this.setLeft(left);
                this.setRight(right);
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Number extends T{
            private Ast.Type.T type;
            public Ast.Type.T getType() { return this.type; }
            public void setType(Ast.Type.T type) { this.type = type; }
            private Object value;
            public Object getValue() { return this.value; }
            public void setValue(Object value) { this.value = value; }

            public Number(Ast.Type.T t, Object o,int lineNumber) {
                this.type = t;
                this.value = o;
                this.setLineNum(lineNumber);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class True extends T{
           public True(int lineNum){
                this.setLineNum(lineNum);
           }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class False extends T{
            public False(int lineNum){
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Str extends T{
            private String value;
            public String getValue() { return this.value; }
            public void setValue(String value) { this.value = value; }
            public Str(String value,int lineNum){
                this.value = value;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        public static class Id extends T {
            private String id;
            public String getId() { return this.id; }
            public void setId(String id) { this.id = id; }
            private Type.T type;
            public Type.T getType() { return this.type; }
            public void setType(Type.T type) { this.type = type; }

            public Id(String id, int lineNum)
            {
                this.id = id;
                this.setLineNum(lineNum);
            }

            public Id(String id, Type.T type, int lineNum) {
                this.id = id;
                this.type = type;
                this.setLineNum(lineNum);
            }

            // Constructor with SourceSpan
            public Id(String id, site.ilemon.util.SourceSpan span)
            {
                this.id = id;
                this.setSpan(span);
            }

            public Id(String id, Type.T type, site.ilemon.util.SourceSpan span) {
                this.id = id;
                this.type = type;
                this.setSpan(span);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        // Array access expression: arr[index]
        public static class ArrayAccess extends T {
            private String arrayName;
            public String getArrayName() { return this.arrayName; }
            public void setArrayName(String arrayName) { this.arrayName = arrayName; }
            private Expr.T index;
            public Expr.T getIndex() { return this.index; }
            public void setIndex(Expr.T index) { this.index = index; }
            private Type.T elementType;
            public Type.T getElementType() { return this.elementType; }
            public void setElementType(Type.T elementType) { this.elementType = elementType; }

            public ArrayAccess(String arrayName, Expr.T index, int lineNum) {
                this.arrayName = arrayName;
                this.index = index;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

        // Array length expression: arr.length
        public static class ArrayLength extends T {
            private String arrayName;
            public String getArrayName() { return this.arrayName; }
            public void setArrayName(String arrayName) { this.arrayName = arrayName; }

            public ArrayLength(String arrayName, int lineNum) {
                this.arrayName = arrayName;
                this.setLineNum(lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }

    }


    public static class Method {
        public static abstract class T {
            private int lineNum;
            public int getLineNum() { return this.lineNum; }
            public void setLineNum(int lineNum) { this.lineNum = lineNum; }
            private SourceSpan span;
            public SourceSpan getSpan() { return this.span; }
            public void setSpan(SourceSpan span) { this.span = span; }
            public abstract void accept(ISemanticVisitor v);
        }

        public static class MethodSingle extends T {
            private Visibility visibility = Visibility.PRIVATE;
            public Visibility getVisibility() { return visibility; }
            public void setVisibility(Visibility visibility) { this.visibility = visibility; }
            private Ast.Type.T retType;
            public Ast.Type.T getRetType() { return this.retType; }
            public void setRetType(Ast.Type.T retType) { this.retType = retType; }
            private String id;
            public String getId() { return this.id; }
            public void setId(String id) { this.id = id; }
            private ArrayList<Declare.T> formals;
            public ArrayList<Declare.T> getFormals() { return this.formals; }
            public void setFormals(ArrayList<Declare.T> formals) { this.formals = formals; }
            private ArrayList<Declare.T> locals;
            public ArrayList<Declare.T> getLocals() { return this.locals; }
            public void setLocals(ArrayList<Declare.T> locals) { this.locals = locals; }
            private ArrayList<Stmt.T> stms;
            public ArrayList<Stmt.T> getStms() { return this.stms; }
            public void setStms(ArrayList<Stmt.T> stms) { this.stms = stms; }
            private Ast.Stmt.T retExp;
            public Ast.Stmt.T getRetExp() { return this.retExp; }
            public void setRetExp(Ast.Stmt.T retExp) { this.retExp = retExp; }

            public MethodSingle(Ast.Type.T  retType, String id,
                                ArrayList<Declare.T> formals,
                                ArrayList<Declare.T> locals,
                                ArrayList<Stmt.T> stms,
                                Ast.Stmt.T retExp,int lineNum) {
                this.retType = retType;
                this.id = id;
                this.formals = formals;
                this.locals = locals;
                this.stms = stms;
                this.retExp = retExp;
                this.setLineNum(lineNum);
            }

            public MethodSingle(Ast.Type.T retType, String id, String classId,
                                ArrayList<Declare.T> formals,
                                ArrayList<Declare.T> locals,
                                ArrayList<Stmt.T> stms,
                                Ast.Stmt.T retExp, int lineNum, int index) {
                this(retType, id, formals, locals, stms, retExp, lineNum);
            }

            @Override
            public void accept(ISemanticVisitor v) {
                v.visit(this);
            }
        }
    }
}
