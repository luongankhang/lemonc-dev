import java.io.*;
import java.nio.file.*;
import java.util.*;

public class StackTracer {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("target/lemonc/StructArrayDemo.class"));
        System.out.println("File size: " + data.length);
        
        // Parse constant pool properly
        int idx = 8;
        int cpCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        String[] cp = new String[cpCount + 1];
        
        while (idx < data.length && cpCount > 1) {
            int start = idx;
            int tag = data[idx] & 0xFF;
            switch (tag) {
                case 1: // Utf8
                    int len = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    cp[start] = new String(data, idx+3, len);
                    idx += 3 + len;
                    break;
                case 3: // Integer
                    cp[start] = "int(" + ((data[idx+1]<<24)|(data[idx+2]<<16)|(data[idx+3]<<8)|data[idx+4]) + ")";
                    idx += 5;
                    break;
                case 7: // Class
                    cp[start] = "C#" + (((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF));
                    idx += 3;
                    break;
                case 8: // String
                    cp[start] = "S#" + (((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF));
                    idx += 3;
                    break;
                case 9: // Fieldref
                    cp[start] = "F#" + (((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF)) + "." + (((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF));
                    idx += 5;
                    break;
                case 10: // Methodref
                    cp[start] = "M#" + (((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF)) + "." + (((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF));
                    idx += 5;
                    break;
                case 12: // NameAndType
                    cp[start] = "NAT#" + (((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF)) + ":" + (((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF));
                    idx += 5;
                    break;
                case 5: // Long
                    cp[start] = "long";
                    cp[start+1] = null;
                    idx += 9;
                    break;
                case 6: // Double
                    cp[start] = "double";
                    cp[start+1] = null;
                    idx += 9;
                    break;
                default:
                    System.err.println("Unknown tag " + tag + " at " + idx);
                    idx++;
                    break;
            }
            cpCount--;
        }
        
        // Find methods
        idx = 8;
        cpCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        while (idx < data.length && cpCount > 1) {
            int tag = data[idx] & 0xFF;
            if (tag == 1) { int l = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF); idx += 3+l; }
            else if (tag == 3 || tag == 4) idx += 5;
            else if (tag == 5 || tag == 6) { idx += 9; cpCount--; }
            else if (tag == 7 || tag == 8) idx += 3;
            else if (tag == 9 || tag == 10 || tag == 12) idx += 5;
            else idx++;
            cpCount--;
        }
        
        int access = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        idx += 6; // access + this + super
        int ifaceCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        idx += 2 + ifaceCount * 2;
        
        int fieldCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        idx += 2;
        for (int i = 0; i < fieldCount; i++) {
            idx += 8; // access + name + desc
            int faCount = ((data[idx-4] & 0xFF) << 8) | (data[idx-3] & 0xFF);
            idx += 2;
            for (int j = 0; j < faCount; j++) {
                int aNameLen = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                idx += 4 + aNameLen;
                int aLen = ((data[idx] & 0xFF) << 24) | ((data[idx+1] & 0xFF) << 16) | ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                idx += 4 + aLen;
            }
        }
        
        int methodCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        idx += 2;
        for (int mi = 0; mi < methodCount; mi++) {
            int ma = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
            int mname = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
            int mdesc = ((data[idx+4] & 0xFF) << 8) | (data[idx+5] & 0xFF);
            idx += 6;
            int mattrCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
            idx += 2;
            
            System.out.println("Method: " + cp[mname] + cp[mdesc] + " access=0x" + Integer.toHexString(ma));
            
            if (cp[mname].equals("main") && cp[mdesc].equals("([Ljava/lang/String;)V")) {
                // Find Code attribute
                for (int ai = 0; ai < mattrCount; ai++) {
                    int aNameLen = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                    String aName = new String(data, idx+4, aNameLen);
                    idx += 4 + aNameLen;
                    int aLen = ((data[idx] & 0xFF) << 24) | ((data[idx+1] & 0xFF) << 16) | ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                    idx += 4;
                    
                    if (aName.equals("Code")) {
                        int maxStack = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
                        int maxLocals = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                        int codeLen = ((data[idx+4] & 0xFF) << 24) | ((data[idx+5] & 0xFF) << 16) | ((data[idx+6] & 0xFF) << 8) | (data[idx+7] & 0xFF);
                        idx += 8;
                        byte[] code = Arrays.copyOfRange(data, idx, idx + codeLen);
                        
                        System.out.println("max_stack=" + maxStack + " max_locals=" + maxLocals + " code_len=" + codeLen);
                        trace(code);
                    }
                    idx += aLen;
                }
            } else {
                for (int ai = 0; ai < mattrCount; ai++) {
                    int aNameLen = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                    idx += 4 + aNameLen;
                    int aLen = ((data[idx] & 0xFF) << 24) | ((data[idx+1] & 0xFF) << 16) | ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                    idx += 4 + aLen;
                }
            }
        }
    }
    
    static void trace(byte[] code) {
        // Track types: 0=int/bool/enum, 1=long, 2=float, 3=double, 4=ref, 5=array
        int[] locals = new int[30];
        Arrays.fill(locals, 0);
        
        // Deque-based stack
        int[] stack = new int[64];
        int sp = 0;
        
        Map<Integer, String> names = new HashMap<>();
        names.put(0x12,"ldc_u1"); names.put(0x13,"ldc_u2"); names.put(0x14,"ldc2_w");
        names.put(0x15,"iload"); names.put(0x16,"lload"); names.put(0x17,"fload"); names.put(0x18,"dload"); names.put(0x19,"aload");
        names.put(0x36,"istore"); names.put(0x37,"lstore"); names.put(0x38,"fstore"); names.put(0x39,"dstore"); names.put(0x3A,"astore");
        names.put(0x4F,"iastore"); names.put(0x50,"lastore"); names.put(0x51,"fastore"); names.put(0x52,"dastore"); names.put(0x53,"aastore");
        names.put(0x2E,"iaload"); names.put(0x2F,"laload"); names.put(0x30,"faload"); names.put(0x31,"daload"); names.put(0x32,"aaload");
        names.put(0xB4,"getfield"); names.put(0xB5,"putfield");
        names.put(0xBB,"new"); names.put(0xBC,"newarray");
        names.put(0xA7,"goto"); names.put(0xB1,"return"); names.put(0xAC,"ireturn");
        names.put(0xB7,"invokespecial"); names.put(0xB6,"invokevirtual"); names.put(0xB8,"invokestatic");
        names.put(0x59,"dup"); names.put(0x5F,"swap"); names.put(0x57,"pop"); names.put(0x58,"pop2");
        names.put(0x60,"iadd"); names.put(0x62,"fadd"); names.put(0x64,"ladd"); names.put(0x65,"dadd");
        names.put(0x61,"isub"); names.put(0x63,"fsub"); names.put(0x67,"lsub"); names.put(0x68,"dsub");
        names.put(0x66,"imul"); names.put(0x69,"fmul"); names.put(0x6B,"lmul"); names.put(0x6C,"dmul");
        names.put(0x6D,"idiv"); names.put(0x6E,"fdiv"); names.put(0x6F,"ldiv"); names.put(0x70,"ddiv");
        names.put(0x71,"irem"); names.put(0x72,"frem"); names.put(0x73,"lrem"); names.put(0x74,"drem");
        names.put(0x75,"iand"); names.put(0x76,"land"); names.put(0x77,"dand");
        names.put(0x78,"ior"); names.put(0x79,"lor"); names.put(0x7A,"dorp");
        names.put(0x7B,"ixor"); names.put(0x7C,"lxor"); names.put(0x7D,"dxor");
        names.put(0x7E,"ineg"); names.put(0x7F,"lneg"); names.put(0x80,"dneg");
        names.put(0x84,"iinc");
        names.put(0x94,"lcmp"); names.put(0x95,"fcmpl"); names.put(0x96,"fcmpg");
        names.put(0x97,"dcmpl"); names.put(0x98,"dcmpg");
        names.put(0x99,"ifeq"); names.put(0x9A,"ifne"); names.put(0x9B,"iflt"); names.put(0x9C,"ifge");
        names.put(0x9D,"ifgt"); names.put(0x9E,"ifle");
        names.put(0xA0,"if_icmpeq"); names.put(0xA1,"if_icmpne"); names.put(0xA2,"if_icmplt");
        names.put(0xA3,"if_icmpge"); names.put(0xA4,"if_icmpgt"); names.put(0xA5,"if_icmple");
        names.put(0xA6,"if_acmpeq"); names.put(0xA7,"if_acmpne");
        names.put(0xC6,"ifnull"); names.put(0xC7,"ifnonnull");
        names.put(0x9F,"i2l"); names.put(0xA0,"i2f"); names.put(0xA1,"i2d");
        names.put(0xA2,"l2i"); names.put(0xA3,"l2f"); names.put(0xA4,"l2d");
        names.put(0xA5,"f2i"); names.put(0xA6,"f2l"); names.put(0xA7,"f2d");
        names.put(0xA8,"d2i"); names.put(0xA9,"d2l"); names.put(0xAA,"d2f");
        names.put(0xAB,"i2b"); names.put(0xAC,"i2c"); names.put(0xAD,"i2s");
        names.put(0xAE,"lcmp"); // already defined
        
        int pos = 0;
        while (pos < code.length) {
            int op = code[pos] & 0xFF;
            String name = names.get(op);
            if (name == null) name = "0x" + Integer.toHexString(op);
            
            try {
                switch (op) {
                    case 0x13: case 0x14: case 0xB4: case 0xB5: case 0xBB: case 0xB6: case 0xB7: case 0xB8: case 0xBD: {
                        int arg = ((code[pos+1] & 0xFF) << 8) | (code[pos+2] & 0xFF);
                        String type = describeOp(op, arg);
                        System.out.printf("  %3d: %-15s arg=%3d | stk=[%s] loc=[%s]%n", pos, name, arg, fmtStack(stack, sp), fmtLocals(locals));
                        applyOp(op, stack, sp, locals);
                        pos += 3;
                        break;
                    }
                    case 0x12: {
                        int arg = code[pos+1] & 0xFF;
                        System.out.printf("  %3d: %-15s arg=%3d | stk=[%s] loc=[%s]%n", pos, name, arg, fmtStack(stack, sp), fmtLocals(locals));
                        stack[sp++] = 0; // ldc pushes int
                        pos += 2;
                        break;
                    }
                    case 0xA7: case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E:
                    case 0xC6: case 0xC7: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4: case 0xA5:
                    case 0xA6: {
                        int off = (short)((code[pos+1] << 8) | (code[pos+2] & 0xFF));
                        System.out.printf("  %3d: %-15s off=%4d | stk=[%s] loc=[%s]%n", pos, name, off, fmtStack(stack, sp), fmtLocals(locals));
                        sp--; // pops condition
                        pos += 3;
                        break;
                    }
                    case 0xBC: {
                        int arg = code[pos+1] & 0xFF;
                        System.out.printf("  %3d: %-15s arg=%d | stk=[%s] loc=[%s]%n", pos, name, arg, fmtStack(stack, sp), fmtLocals(locals));
                        // newarray: pops 1, pushes array ref
                        if (sp < 1) throw new RuntimeException("stack underflow at newarray");
                        sp--; // pop size
                        stack[sp++] = 5; // push array
                        pos += 2;
                        break;
                    }
                    case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
                    case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A:
                    case 0x2E: case 0x2F: case 0x30: case 0x31: case 0x32:
                    case 0x4F: case 0x50: case 0x51: case 0x52: case 0x53: {
                        int arg = code[pos+1] & 0xFF;
                        System.out.printf("  %3d: %-15s arg=%3d | stk=[%s] loc=[%s]%n", pos, name, arg, fmtStack(stack, sp), fmtLocals(locals));
                        applyLocalOp(op, arg, stack, sp, locals);
                        pos += 2;
                        break;
                    }
                    case 0x59: case 0x5F: case 0x57: case 0x58: case 0x5A: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(stack, sp), fmtLocals(locals));
                        applySimpleOp(op, stack, sp);
                        pos++;
                        break;
                    }
                    case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(stack, sp), fmtLocals(locals));
                        stack[sp++] = 0; // const pushes int
                        pos++;
                        break;
                    }
                    case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65: case 0x66: case 0x67:
                    case 0x68: case 0x69: case 0x6A: case 0x6B: case 0x6C: case 0x6D: case 0x6E: case 0x6F:
                    case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75: case 0x76: case 0x77:
                    case 0x78: case 0x79: case 0x7A: case 0x7B: case 0x7C: case 0x7D: case 0x7E: case 0x7F:
                    case 0x80: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(stack, sp), fmtLocals(locals));
                        // Most binary ops: pop 2, push 1 (or 2 for long/double)
                        if (op >= 0x64 && op <= 0x67) { // ladd, lsub, lmul, ldiv
                            if (sp < 2) throw new RuntimeException("stack underflow at " + name);
                            sp -= 2;
                            stack[sp++] = 1;
                        } else {
                            if (sp < 2) throw new RuntimeException("stack underflow at " + name);
                            sp -= 2;
                            stack[sp++] = 0;
                        }
                        pos++;
                        break;
                    }
                    case 0x94: case 0x95: case 0x96: case 0x97: case 0x98: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(stack, sp), fmtLocals(locals));
                        if (op == 0x94) { // lcmp
                            if (sp < 2) throw new RuntimeException("stack underflow at " + name);
                            sp -= 2;
                            stack[sp++] = 0;
                        } else {
                            if (sp < 2) throw new RuntimeException("stack underflow at " + name);
                            sp -= 2;
                            stack[sp++] = 0;
                        }
                        pos++;
                        break;
                    }
                    case 0xB1: case 0xAC: case 0xAD: case 0xAE: case 0xAF: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(stack, sp), fmtLocals(locals));
                        pos++;
                        break;
                    }
                    case 0xBF: { // athrow
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(stack, sp), fmtLocals(locals));
                        sp--;
                        pos++;
                        break;
                    }
                    default:
                        System.out.printf("  %3d: %-15s UNKNOWN | stk=[%s] loc=[%s]%n", pos, name, fmtStack(stack, sp), fmtLocals(locals));
                        pos++;
                        break;
                }
            } catch (Exception e) {
                System.out.printf("  %3d: ERROR %s | stk=[%s] loc=[%s]%n", pos, e.getMessage(), fmtStack(stack, sp), fmtLocals(locals));
                break;
            }
        }
        System.out.println("Final stack: [" + fmtStack(stack, sp) + "]");
    }
    
    static String describeOp(int op, int arg) {
        if (op == 0xBB) return "ref(new)";
        if (op == 0xBC) return "array(newarray)";
        if (op == 0xBD) return "ref(anewarray)";
        if (op == 0xB4 || op == 0xB5) return "ref(field)";
        if (op == 0xB6 || op == 0xB7 || op == 0xB8) return "void(call)";
        return "?";
    }
    
    static void applyOp(int op, int[] stack, int[] sp, int[] locals) {
        switch (op) {
            case 0xBB: // new
                stack[sp[0]++] = 4; // ref
                break;
            case 0xBC: // newarray - handled separately
                break;
            case 0xBD: // anewarray
                if (sp[0] < 1) throw new RuntimeException("underflow");
                sp[0]--;
                stack[sp[0]++] = 5; // array
                break;
            case 0xB4: // getfield: pops ref, pushes field value
                if (sp[0] < 1) throw new RuntimeException("underflow at getfield");
                sp[0]--;
                stack[sp[0]++] = 0; // assume int for now
                break;
            case 0xB5: // putfield: pops ref + value
                if (sp[0] < 2) throw new RuntimeException("underflow at putfield");
                sp[0] -= 2;
                break;
            case 0xB6: case 0xB7: case 0xB8: // invoke
                // Assume void for now
                break;
        }
    }
    
    static void applyLocalOp(int op, int arg, int[] stack, int sp, int[] locals) {
        // Load opcodes push to stack
        if (op >= 0x15 && op <= 0x19) {
            int type = locals[arg];
            if (type == 1 || type == 3) { // long or double
                if (sp + 2 > stack.length) throw new RuntimeException("stack overflow");
                stack[sp++] = type;
                stack[sp++] = type;
            } else {
                if (sp >= stack.length) throw new RuntimeException("stack overflow");
                stack[sp++] = type;
            }
        }
        // Store opcodes pop from stack
        else if (op >= 0x36 && op <= 0x3A) {
            if (sp < 1) throw new RuntimeException("stack underflow at store");
            int val = stack[--sp];
            locals[arg] = val;
        }
        // Array load ops: pop 2 (array+index), push result
        else if (op == 0x2E || op == 0x2F || op == 0x30 || op == 0x31 || op == 0x32) {
            if (sp < 2) throw new RuntimeException("stack underflow at " + opcodeName(op) + "load");
            int arrType = stack[--sp]; // array ref
            sp--; // index (int)
            // Push loaded value type
            if (arrType == 5) { // array
                // For int[], push int (0)
                if (sp >= stack.length) throw new RuntimeException("stack overflow");
                stack[sp++] = 0;
            }
        }
        // Array store ops: pop 3 (array+index+value)
        else if (op == 0x4F || op == 0x50 || op == 0x51 || op == 0x52 || op == 0x53) {
            if (sp < 3) throw new RuntimeException("stack underflow at " + opcodeName(op) + "store");
            sp -= 3;
        }
    }
    
    static void applySimpleOp(int op, int[] stack, int[] sp) {
        switch (op) {
            case 0x59: // dup
                if (sp < 1) throw new RuntimeException("dup underflow");
                stack[sp] = stack[sp-1];
                sp++;
                break;
            case 0x5F: // swap
                if (sp < 2) throw new RuntimeException("swap underflow");
                int tmp = stack[sp-1];
                stack[sp-1] = stack[sp-2];
                stack[sp-2] = tmp;
                break;
            case 0x57: // pop
                if (sp < 1) throw new RuntimeException("pop underflow");
                sp--;
                break;
            case 0x58: // pop2
                if (sp < 2) throw new RuntimeException("pop2 underflow");
                sp -= 2;
                break;
            case 0x5A: // dup_x1
                if (sp < 2) throw new RuntimeException("dup_x1 underflow");
                int t = stack[sp-1];
                int b = stack[sp-2];
                stack[sp] = t;
                stack[sp-1] = b;
                stack[sp-2] = t;
                sp++;
                break;
        }
    }
    
    static String opcodeName(int op) {
        if (op == 0x2E) return "i";
        if (op == 0x2F) return "l";
        if (op == 0x30) return "f";
        if (op == 0x31) return "d";
        if (op == 0x32) return "a";
        if (op == 0x4F) return "i";
        if (op == 0x50) return "l";
        if (op == 0x51) return "f";
        if (op == 0x52) return "d";
        if (op == 0x53) return "a";
        return "?";
    }
    
    static String fmtStack(int[] stack, int sp) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sp; i++) {
            if (i > 0) sb.append(",");
            sb.append(typeName(stack[i]));
        }
        sb.append("]");
        return sb.toString();
    }
    
    static String fmtLocals(int[] locals) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < locals.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(typeName(locals[i]));
        }
        sb.append("]");
        return sb.toString();
    }
    
    static String typeName(int t) {
        switch(t) {
            case 0: return "int";
            case 1: return "long";
            case 2: return "float";
            case 3: return "double";
            case 4: return "ref";
            case 5: return "array";
            default: return "?" + t;
        }
    }
}
