package site.ilemon.codegen.ast;

import site.ilemon.ast.Ast.Type.TypeKind;
import java.util.List;

/**
 * Created by andy on 2019/8/5.
 */
public class Ast {

    // program
    public static class Program {

        public static class T{
}

        public static class ProgramSingle extends T{
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }

public MainClass.MainClassSingle mainClass;

            public ProgramSingle(MainClass.MainClassSingle mainClass) {
                this.mainClass = mainClass;
            }
        }
    }

    // MainClass
    public static class MainClass {
        public static class MainClassSingle {

public List<Method.MethodSingle> methods;
            public String id;

            public MainClassSingle(String id,List<Method.MethodSingle> methods) {
                this.id = id;
                this.methods = methods;
            }
        }
    }

    // Type
    public static class Type {
        public static abstract class T {
            public abstract void accept(site.ilemon.codegen.Visitor v);
            public abstract TypeKind getKind();
        }

        public static class ClassType extends T
        {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public String id;

            public ClassType(String id)
            {
                this.id = id;
            }

            @Override
            public TypeKind getKind() { return null; } // ClassType has no standard kind
            @Override
            public String toString()
            {
                return this.id;
            }
        }

        public static class Bool extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.BOOL; }
            @Override
            public String toString()
            {
                return "@bool";
            }
        }

        public static class Byte extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            @Override
            public TypeKind getKind() { return TypeKind.BYTE; }
            @Override
            public String toString() { return "@byte"; }
        }

        public static class Short extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            public TypeKind getKind() { return TypeKind.SHORT; }
            public String toString() { return "@short"; }
        }

        public static class Char extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            public TypeKind getKind() { return TypeKind.CHAR; }
            public String toString() { return "@char"; }
        }

        public static class Int extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.INT; }
            @Override
            public String toString()
            {
                return "@int";
            }
        }

        public static class Long extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            @Override
            public TypeKind getKind() { return TypeKind.LONG; }
            @Override
            public String toString() { return "@long"; }
        }

        public static class Float extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.FLOAT; }
            @Override
            public String toString()
            {
                return "@float";
            }
        }

        public static class Double extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.DOUBLE; }
            @Override
            public String toString()
            {
                return "@double";
            }
        }

        public static class Str extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.STRING; }
            @Override
            public String toString()
            {
                return "@string";
            }
        }

        public static class Void extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.VOID; }
            @Override
            public String toString()
            {
                return "@void";
            }
        }

        // Array types
        public static class IntArray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.INT_ARRAY; }
            @Override
            public String toString() { return "@int[]"; }
        }

        public static class ByteArray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            @Override
            public TypeKind getKind() { return TypeKind.BYTE_ARRAY; }
            @Override
            public String toString() { return "@byte[]"; }
        }

        public static class ShortArray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            public TypeKind getKind() { return TypeKind.SHORT_ARRAY; }
            public String toString() { return "@short[]"; }
        }

        public static class LongArray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            @Override
            public TypeKind getKind() { return TypeKind.LONG_ARRAY; }
            @Override
            public String toString() { return "@long[]"; }
        }

        public static class FloatArray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.FLOAT_ARRAY; }
            @Override
            public String toString() { return "@float[]"; }
        }

        public static class DoubleArray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.DOUBLE_ARRAY; }
            @Override
            public String toString() { return "@double[]"; }
        }

        public static class BoolArray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
@Override
            public TypeKind getKind() { return TypeKind.BOOL_ARRAY; }
            @Override
            public String toString() { return "@bool[]"; }
        }

        public static class StringArray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            @Override
            public TypeKind getKind() { return TypeKind.STRING_ARRAY; }
            @Override
            public String toString() { return "@string[]"; }
        }
    }


    // Declare
    public static class Declare {
        public static class DeclareSingle
        {

public Type.T type;
            public String id;

            public DeclareSingle(Type.T type, String id)
            {
                this.type = type;
                this.id = id;
            }
        }
    }

    //Stmt
    public static class Stmt {
        public static abstract class T {
            public abstract void accept(site.ilemon.codegen.Visitor v);
        }

        public static class Aload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public int index;

            public Aload(int index)
            {
                this.index = index;
            }
        }

        public static class Areturn extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Astore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public int index;

            public Astore(int index) {
                this.index = index;
            }
        }

        public static class Goto extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label l;

            public Goto(Label l)
            {
                this.l = l;
            }
        }



        public static class Iadd extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Isub extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Imul extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Idiv extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Irem extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Ladd extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }
        public static class Lsub extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }
        public static class Lmul extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }
        public static class Ldiv extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }
        public static class Lrem extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }
        public static class Lcmp extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }

        public static class I2l extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }
        public static class L2f extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }
        public static class L2d extends T { public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); } }


        public static class Fadd extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Fsub extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Fmul extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Fdiv extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Dadd extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Dsub extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Dmul extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Ddiv extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Ificmplt extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label l;

            public Ificmplt(Label l) {
                this.l = l;
            }
        }

        public static class Ificmpgt extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label l;

            public Ificmpgt(Label l) {

                this.l = l;
            }
        }

        /**
         * ifgt
         * Jump when top-of-stack int value is greater than 0.
         */
        public static class Ifgt extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label l;

            public Ifgt(Label l) {

                this.l = l;
            }
        }


        /**
         * Floating-point comparison instructions.
         */
        public static class Fcmpl extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Fcmpl() {

            }
        }

        public static class Fcmpg extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Fcmpg() {

            }
        }

        public static class Ificmple extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label l;

            public Ificmple(Label l) {
                this.l = l;
            }
        }

        public static class Ificmpge extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label l;

            public Ificmpge(Label l) {

                this.l = l;
            }
        }

        public static class Ificmpeq extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label l;

            public Ificmpeq(Label l) {
                this.l = l;
            }
        }

        public static class Ificmpne extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label l;

            public Ificmpne(Label l) {
                this.l = l;
            }
        }

        public static class Iload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public int index;

            public Iload(int index)
            {
                this.index = index;
            }
        }

        public static class Lload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            public int index;
            public Lload(int index) { this.index = index; }
        }

        public static class Fload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public int index;

            public Fload(int index)
            {
                this.index = index;
            }
        }

        public static class Dload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public int index;

            public Dload(int index)
            {
                this.index = index;
            }
        }



        public static class Invokestatic extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public String name;
            public List<Type.T> at;
            public Type.T rt;

            public Invokestatic(String name, List<Type.T> at, Type.T rt) {
                this.name = name;
                this.at = at;
                this.rt = rt;
            }
        }

        public static class Ireturn extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Lreturn extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
        }

        public static class Istore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public int index;

            public Istore(int index) {
                this.index = index;
            }
        }

        public static class Lstore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
            public int index;
            public Lstore(int index) { this.index = index; }
        }

        public static class Freturn extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Fstore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public int index;

            public Fstore(int index) {
                this.index = index;
            }
        }

        public static class Dreturn extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Dstore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public int index;

            public Dstore(int index) {
                this.index = index;
            }
        }

        public static class Dcmpl extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Dcmpl() {
            }
        }

        public static class Dcmpg extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Dcmpg() {
            }
        }

        public static class F2d extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public F2d() {
            }
        }

        public static class I2f extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public I2f() {
            }
        }

        public static class I2d extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public I2d() {
            }
        }


        public static class LabelJ extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Label label;

            public LabelJ(Label label)
            {
                this.label = label;
            }
        }

        public static class Ldc extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Object i;

            public Ldc(Object i)
            {
                this.i = i;
            }
        }



        public static class Printf extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Type.T exprType;

            public String v;

            public Printf(Type.T t,String v) {
                this.exprType = t;
                this.v = v;
            }
        }

        public static class PrintLine extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Pop extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Pop2 extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        // ========== Array-related instructions ==========

        // Create array: newarray int/float/double/boolean
        public static class Newarray extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
public Type.T elementType;
            public Newarray(Type.T elementType) {
                this.elementType = elementType;
            }
        }

        // Load from int array: iaload
        public static class Iaload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        // Store into int array: iastore
        public static class Iastore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        // Load from float array: faload
        public static class Faload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        // Store into float array: fastore
        public static class Fastore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        // Load from double array: daload
        public static class Daload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        // Store into double array: dastore
        public static class Dastore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        // Load from boolean array: baload
        public static class Baload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Laload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
        }

        public static class Saload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
        }

        // Store into boolean array: bastore
        public static class Bastore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

        public static class Lastore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
        }

        public static class Sastore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
        }

        public static class Aaload extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
        }

        public static class Aastore extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
        }

        // Array length: arraylength
        public static class Arraylength extends T {
            public void accept(site.ilemon.codegen.Visitor v) { v.visit(this); }
}

    }

    public static class Method
    {
        public static class MethodSingle
        {

public Type.T retType;
            public String id;
            public String classId;
            public List<Declare.DeclareSingle> formals;
            public List<Declare.DeclareSingle> locals;
            public List<Stmt.T> stms;
            public int index; // number of index
            public int retExp;

            public MethodSingle(Type.T retType, String id, String classId,
                                List<Declare.DeclareSingle> formals,
                                List<Declare.DeclareSingle> locals,
                                List<Stmt.T> stms, int retExp, int index) {
                this.retType = retType;
                this.id = id;
                this.classId = classId;
                this.formals = formals;
                this.locals = locals;
                this.stms = stms;
                this.retExp = retExp;
                this.index = index;
            }
        }
    }

}
