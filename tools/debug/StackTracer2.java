import java.io.*;
import java.nio.file.*;
import java.util.*;

public class StackTracer2 {
    static int sp;
    static int[] stack;
    static int[] locals;
    
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("target/lemonc/StructArrayDemo.class"));
        System.out.println("File size: " + data.length);
        
        // Parse constant pool
        int idx = 8;
        int cpCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        String[] cp = new String[cpCount + 1];
        
        while (idx < data.length && cpCount > 1) {
            int start = idx;
            int tag = data[idx] & 0xFF;
            switch (tag) {
                case 1: {
                    int len = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    cp[start] = new String(data, idx+3, len);
                    idx += 3 + len;
                    break;
                }
                case 3: {
                    int val = ((data[idx+1] & 0xFF) << 24) | ((data[idx+2] & 0xFF) << 16) | ((data[idx+3] & 0xFF) << 8) | (data[idx+4] & 0xFF);
                    cp[start] = "Int(" + val + ")";
                    idx += 5;
                    break;
                }
                case 7: {
                    int nameIdx = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    cp[start] = "C#" + nameIdx;
                    idx += 3;
                    break;
                }
                case 8: {
                    int strIdx = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    cp[start] = "S#" + strIdx;
                    idx += 3;
                    break;
                }
                case 9: {
                    int classIdx = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    int natIdx = ((data[idx+3] & 0xFF) << 8) | (data[idx+4] & 0xFF);
                    cp[start] = "F#" + classIdx + "." + natIdx;
                    idx += 5;
                    break;
                }
                case 10: {
                    int classIdx = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    int natIdx = ((data[idx+3] & 0xFF) << 8) | (data[idx+4] & 0xFF);
                    cp[start] = "M#" + classIdx + "." + natIdx;
                    idx += 5;
                    break;
                }
                case 12: {
                    int nameIdx = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    int descIdx = ((data[idx+3] & 0xFF) << 8) | (data[idx+4] & 0xFF);
                    cp[start] = "NAT#" + nameIdx + ":" + descIdx;
                    idx += 5;
                    break;
                }
                case 5: case 6: {
                    cp[start] = tag == 5 ? "Long" : "Double";
                    cp[start + 1] = null;
                    idx += 9;
                    break;
                }
                default:
                    System.err.println("Unknown tag " + tag + " at " + idx);
                    idx++;
                    break;
            }
            cpCount--;
        }
        
        // Find main method
        idx = 8;
        cpCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        while (idx < data.length && cpCount > 1) {
            int tag = data[idx] & 0xFF;
            if (tag == 1) { int l = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF); idx += 3 + l; }
            else if (tag == 3 || tag == 4) idx += 5;
            else if (tag == 5 || tag == 6) { idx += 9; cpCount--; }
            else if (tag == 7 || tag == 8) idx += 3;
            else if (tag == 9 || tag == 10 || tag == 12) idx += 5;
            else idx++;
            cpCount--;
        }
        
        idx += 6; // access + this + super
        int ifaceCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        idx += 2 + ifaceCount * 2;
        
        int fieldCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        idx += 2;
        for (int i = 0; i < fieldCount; i++) {
            idx += 8;
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
            
            if (cp[mname].equals("main") && cp[mdesc].equals("([Ljava/lang/String;)V")) {
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
        stack = new int[128];
        locals = new int[64];
        Arrays.fill(locals, 0);
        locals[0] = 4; // this is ref
        sp = 0;
        
        Map<Integer, String> names = new HashMap<>();
        names.put(0x12, "ldc"); names.put(0x13, "ldc_w"); names.put(0x14, "ldc2_w");
        names.put(0x15, "iload"); names.put(0x16, "lload"); names.put(0x17, "fload"); names.put(0x18, "dload"); names.put(0x19, "aload");
        names.put(0x36, "istore"); names.put(0x37, "lstore"); names.put(0x38, "fstore"); names.put(0x39, "dstore"); names.put(0x3A, "astore");
        names.put(0x4F, "iastore"); names.put(0x50, "lastore"); names.put(0x51, "fastore"); names.put(0x52, "dastore"); names.put(0x53, "aastore");
        names.put(0x2E, "iaload"); names.put(0x2F, "laload"); names.put(0x30, "faload"); names.put(0x31, "daload"); names.put(0x32, "aaload");
        names.put(0xB4, "getfield"); names.put(0xB5, "putfield");
        names.put(0xBB, "new"); names.put(0xBC, "newarray");
        names.put(0xA7, "goto"); names.put(0xB1, "return"); names.put(0xAC, "ireturn");
        names.put(0xB7, "invokespecial"); names.put(0xB6, "invokevirtual"); names.put(0xB8, "invokestatic");
        names.put(0x59, "dup"); names.put(0x5F, "swap"); names.put(0x57, "pop"); names.put(0x58, "pop2");
        names.put(0x60, "iadd"); names.put(0x61, "isub"); names.put(0x62, "fadd"); names.put(0x63, "fsub");
        names.put(0x64, "ladd"); names.put(0x65, "dadd"); names.put(0x66, "imul"); names.put(0x67, "ldiv");
        names.put(0x68, "idiv"); names.put(0x69, "irem"); names.put(0x6A, "and"); names.put(0x6B, "or");
        names.put(0x6C, "xor"); names.put(0x6D, "shl"); names.put(0x6E, "shr"); names.put(0x6F, "ushr");
        names.put(0x70, "neg"); names.put(0x71, "not");
        names.put(0x99, "ifeq"); names.put(0x9A, "ifne"); names.put(0x9B, "iflt"); names.put(0x9C, "ifge");
        names.put(0x9D, "ifgt"); names.put(0x9E, "ifle");
        names.put(0xC6, "ifnull"); names.put(0xC7, "ifnonnull");
        names.put(0xA0, "if_acmpeq"); names.put(0xA1, "if_acmpne");
        
        int pos = 0;
        while (pos < code.length) {
            int op = code[pos] & 0xFF;
            String name = names.get(op);
            if (name == null) name = "0x" + Integer.toHexString(op);
            
            try {
                switch (op) {
                    case 0x13: case 0x14: case 0xB4: case 0xB5: case 0xBB: case 0xB6: case 0xB7: case 0xB8: case 0xBD: {
                        int arg = ((code[pos+1] & 0xFF) << 8) | (code[pos+2] & 0xFF);
                        System.out.printf("  %3d: %-15s arg=%3d | stk=[%s] loc=[%s]%n", pos, name, arg, fmtStack(), fmtLocals());
                        handle2byteOp(op);
                        pos += 3;
                        break;
                    }
                    case 0x12: {
                        int arg = code[pos+1] & 0xFF;
                        System.out.printf("  %3d: %-15s arg=%3d | stk=[%s] loc=[%s]%n", pos, name, arg, fmtStack(), fmtLocals());
                        push(0);
                        pos += 2;
                        break;
                    }
                    case 0xA7: case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E:
                    case 0xC6: case 0xC7: case 0xA0: case 0xA1: {
                        int off = (short)((code[pos+1] << 8) | (code[pos+2] & 0xFF));
                        System.out.printf("  %3d: %-15s off=%4d | stk=[%s] loc=[%s]%n", pos, name, off, fmtStack(), fmtLocals());
                        pop();
                        pos += 3;
                        break;
                    }
                    case 0xBC: {
                        int arg = code[pos+1] & 0xFF;
                        System.out.printf("  %3d: %-15s arg=%d | stk=[%s] loc=[%s]%n", pos, name, arg, fmtStack(), fmtLocals());
                        pop(); // size
                        push(5); // array ref
                        pos += 2;
                        break;
                    }
                    case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
                    case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A:
                    case 0x2E: case 0x2F: case 0x30: case 0x31: case 0x32:
                    case 0x4F: case 0x50: case 0x51: case 0x52: case 0x53: {
                        int arg = code[pos+1] & 0xFF;
                        System.out.printf("  %3d: %-15s arg=%3d | stk=[%s] loc=[%s]%n", pos, name, arg, fmtStack(), fmtLocals());
                        handleLocalOp(op, arg);
                        pos += 2;
                        break;
                    }
                    case 0x59: case 0x5F: case 0x57: case 0x58: case 0x5A: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(), fmtLocals());
                        handleSimpleOp(op);
                        pos++;
                        break;
                    }
                    case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(), fmtLocals());
                        push(0);
                        pos++;
                        break;
                    }
                    case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65: case 0x66: case 0x67:
                    case 0x68: case 0x69: case 0x6A: case 0x6B: case 0x6C: case 0x6D: case 0x6E: case 0x6F:
                    case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75: case 0x76: case 0x77:
                    case 0x78: case 0x79: case 0x7A: case 0x7B: case 0x7C: case 0x7D: case 0x7E: case 0x7F:
                    case 0x80: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(), fmtLocals());
                        pop2Push1();
                        pos++;
                        break;
                    }
                    case 0x94: case 0x95: case 0x96: case 0x97: case 0x98: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(), fmtLocals());
                        pop2Push1();
                        pos++;
                        break;
                    }
                    case 0xB1: case 0xAC: case 0xAD: case 0xAE: case 0xAF: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(), fmtLocals());
                        pos++;
                        break;
                    }
                    case 0xBF: {
                        System.out.printf("  %3d: %-15s | stk=[%s] loc=[%s]%n", pos, name, fmtStack(), fmtLocals());
                        pop();
                        pos++;
                        break;
                    }
                    default:
                        System.out.printf("  %3d: %-15s UNKNOWN | stk=[%s] loc=[%s]%n", pos, name, fmtStack(), fmtLocals());
                        pos++;
                        break;
                }
            } catch (Exception e) {
                System.out.printf("  %3d: ERROR %s | stk=[%s] loc=[%s]%n", pos, e.getMessage(), fmtStack(), fmtLocals());
                break;
            }
        }
        System.out.println("Final stack: [" + fmtStack() + "]");
    }
    
    static void push(int type) {
        if (sp >= stack.length) throw new RuntimeException("stack overflow at pos");
        stack[sp++] = type;
    }
    
    static int pop() {
        if (sp < 1) throw new RuntimeException("stack underflow");
        return stack[--sp];
    }
    
    static void pop2Push1() {
        pop(); pop(); push(0);
    }
    
    static void handle2byteOp(int op) {
        switch (op) {
            case 0xBB: // new
                push(4);
                break;
            case 0xBC: // newarray - handled separately
                break;
            case 0xBD: // anewarray
                pop(); push(5);
                break;
            case 0xB4: // getfield: pops ref, pushes field (assume int for now)
                if (sp < 1) throw new RuntimeException("underflow at getfield");
                pop(); push(0);
                break;
            case 0xB5: // putfield: pops ref + value
                if (sp < 2) throw new RuntimeException("underflow at putfield");
                pop(); pop();
                break;
            case 0xB6: case 0xB7: case 0xB8: // invoke
                // Assume void for now
                break;
        }
    }
    
    static void handleLocalOp(int op, int arg) {
        if (op >= 0x15 && op <= 0x19) {
            // Load
            int type = locals[arg];
            push(type);
        } else if (op >= 0x36 && op <= 0x3A) {
            // Store
            if (sp < 1) throw new RuntimeException("underflow at store");
            locals[arg] = pop();
        } else if (op == 0x2E || op == 0x2F || op == 0x30 || op == 0x31 || op == 0x32) {
            // Array load: pop array, pop index, push element
            if (sp < 2) throw new RuntimeException("underflow at aload");
            int arrType = pop();
            pop(); // index
            if (arrType == 5) push(0); // int array loads int
            else push(4);
        } else if (op == 0x4F || op == 0x50 || op == 0x51 || op == 0x52 || op == 0x53) {
            // Array store: pop array, pop index, pop value
            if (sp < 3) throw new RuntimeException("underflow at astore");
            pop(); pop(); pop();
        }
    }
    
    static void handleSimpleOp(int op) {
        switch (op) {
            case 0x59: // dup
                if (sp < 1) throw new RuntimeException("dup underflow");
                push(stack[sp-1]);
                break;
            case 0x5F: // swap
                if (sp < 2) throw new RuntimeException("swap underflow");
                int a = pop(); int b = pop(); push(a); push(b);
                break;
            case 0x57: // pop
                pop();
                break;
            case 0x58: // pop2
                pop(); pop();
                break;
            case 0x5A: // dup_x1
                if (sp < 2) throw new RuntimeException("dup_x1 underflow");
                int t = stack[sp-1];
                int b2 = stack[sp-2];
                stack[sp-2] = t;
                stack[sp-1] = b2;
                push(t);
                break;
        }
    }
    
    static String fmtStack() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sp; i++) {
            if (i > 0) sb.append(",");
            sb.append(typeName(stack[i]));
        }
        sb.append("]");
        return sb.toString();
    }
    
    static String fmtLocals() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20 && i < locals.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(typeName(locals[i]));
        }
        sb.append("...]");
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
