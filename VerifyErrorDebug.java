import java.io.*;
import java.nio.file.*;
import java.util.*;

public class VerifyErrorDebug {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("target/lemonc/StructArrayDemo.class"));
        
        // Find Code attribute
        int idx = parseAndSkipCP(data);
        
        // Skip class info
        idx += 2 + 2 + 2; // access, this, super
        int ifaceCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2 + ifaceCount * 2;
        
        // Skip fields
        int fieldCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        for (int i = 0; i < fieldCount; i++) {
            idx += 8; // access + name + desc
            int attrCount = ((data[idx-4]&0xFF)<<8)|(data[idx-3]&0xFF);
            idx += 2;
            for (int j = 0; j < attrCount; j++) {
                int aNameLen = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4 + aNameLen;
                int aLen = ((data[idx]&0xFF)<<24)|((data[idx+1]&0xFF)<<16)|((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4 + aLen;
            }
        }
        
        // Find main method
        int methodCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        for (int i = 0; i < methodCount; i++) {
            int ma = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            int mname = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
            int mdesc = ((data[idx+4]&0xFF)<<8)|(data[idx+5]&0xFF);
            idx += 6;
            int mattrCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            idx += 2;
            if (mname != 1 || mdesc != 4) { // skip non-main
                for (int j = 0; j < mattrCount; j++) {
                    int aNameLen = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    idx += 4 + aNameLen;
                    int aLen = ((data[idx]&0xFF)<<24)|((data[idx+1]&0xFF)<<16)|((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    idx += 4 + aLen;
                }
                continue;
            }
            
            for (int j = 0; j < mattrCount; j++) {
                int aNameLen = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                String aName = new String(data, idx+4, aNameLen);
                idx += 4 + aNameLen;
                int aLen = ((data[idx]&0xFF)<<24)|((data[idx+1]&0xFF)<<16)|((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4;
                
                if (aName.equals("Code")) {
                    int maxStack = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
                    int maxLocals = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    int codeLen = ((data[idx+4]&0xFF)<<24)|((data[idx+5]&0xFF)<<16)|((data[idx+6]&0xFF)<<8)|(data[idx+7]&0xFF);
                    idx += 8;
                    byte[] code = Arrays.copyOfRange(data, idx, idx + codeLen);
                    
                    System.out.println("Code attribute found!");
                    System.out.println("max_stack=" + maxStack + " max_locals=" + maxLocals + " code_len=" + codeLen);
                    System.out.println("First 50 bytes: " + Arrays.toString(Arrays.copyOf(code, Math.min(50, code.length))));
                    
                    // Track types on stack
                    trackTypes(code, maxLocals);
                }
                idx += aLen;
            }
        }
    }
    
    static int parseAndSkipCP(byte[] data) {
        int idx = 8;
        int cpCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        for (int i = 0; i < cpCount && idx < data.length; i++) {
            int tag = data[idx] & 0xFF;
            if (tag == 1) { int l = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF); idx += 3+l; }
            else if (tag == 3 || tag == 4) idx += 5;
            else if (tag == 5 || tag == 6) { idx += 9; i++; } // takes 2 slots
            else if (tag == 7 || tag == 8 || tag == 12 || tag == 15 || tag == 16 || tag == 17 || tag == 18) {
                if (tag == 16) idx += 4; else idx += 3;
                if (tag == 12 || tag == 15) idx += 2;
            }
            else if (tag == 9 || tag == 10 || tag == 11) idx += 5;
            else idx++;
        }
        return idx;
    }
    
    static void trackTypes(byte[] code, int maxLocals) {
        // Simple type tracking
        // Types: 0=int, 1=long, 2=float, 3=double, 4=ref, 5=array
        int[] locals = new int[maxLocals];
        Arrays.fill(locals, 0); // default int
        locals[0] = 4; // this (struct ref)
        
        Deque<Integer> stack = new ArrayDeque<>();
        
        Map<Integer, String> opNames = new HashMap<>();
        opNames.put(0x12,"ldc_u1"); opNames.put(0x13,"ldc_u2"); opNames.put(0x14,"ldc2_w");
        opNames.put(0x15,"iload"); opNames.put(0x16,"lload"); opNames.put(0x17,"fload"); opNames.put(0x18,"dload"); opNames.put(0x19,"aload");
        opNames.put(0x36,"istore"); opNames.put(0x37,"lstore"); opNames.put(0x38,"fstore"); opNames.put(0x39,"dstore"); opNames.put(0x3A,"astore");
        opNames.put(0x4F,"iastore"); opNames.put(0x50,"lastore"); opNames.put(0x51,"fastore"); opNames.put(0x52,"dastore"); opNames.put(0x53,"aastore");
        opNames.put(0x2E,"iaload"); opNames.put(0x2F,"laload"); opNames.put(0x30,"faload"); opNames.put(0x31,"daload"); opNames.put(0x32,"aaload");
        opNames.put(0xB4,"getfield"); opNames.put(0xB5,"putfield");
        opNames.put(0xBB,"new"); opNames.put(0xBC,"newarray");
        opNames.put(0xA7,"goto"); opNames.put(0xB1,"return"); opNames.put(0xAC,"ireturn");
        opNames.put(0xB7,"invokespecial"); opNames.put(0xB6,"invokevirtual"); opNames.put(0xB8,"invokestatic");
        opNames.put(0x59,"dup"); opNames.put(0x5F,"swap"); opNames.put(0x57,"pop"); opNames.put(0x58,"pop2");
        opNames.put(0x60,"iadd"); opNames.put(0x62,"fadd"); opNames.put(0x64,"ladd"); opNames.put(0x65,"dadd");
        opNames.put(0x99,"ifeq"); opNames.put(0x9A,"ifne"); opNames.put(0x9B,"iflt"); opNames.put(0x9C,"ifge");
        opNames.put(0x9D,"ifgt"); opNames.put(0x9E,"ifle");
        opNames.put(0xC6,"ifnull"); opNames.put(0xC7,"ifnonnull");
        
        int pos = 0;
        while (pos < code.length) {
            int op = code[pos] & 0xFF;
            String name = opNames.get(op);
            if (name == null) name = "0x" + Integer.toHexString(op);
            
            int size;
            try {
                if (op == 0x13 || op == 0x14 || op == 0xB4 || op == 0xB5 || op == 0xBB || op == 0xB7 || op == 0xB6 || op == 0xB8 || op == 0xBD) {
                    int arg = ((code[pos+1]&0xFF)<<8)|(code[pos+2]&0xFF);
                    System.out.printf("  %3d: %-15s arg=%d | stack=[%s] locals=[%s]%n", pos, name, arg, formatStack(stack), formatLocals(locals));
                    if (op == 0xBB) stack.push(4); // new creates ref
                    else if (op == 0xB4 || op == 0xB5 || op == 0xB6 || op == 0xB7 || op == 0xB8) {
                        // assume field/method returns int
                        stack.push(0);
                    }
                    size = 3;
                } else if (op == 0x12) {
                    int arg = code[pos+1] & 0xFF;
                    System.out.printf("  %3d: %-15s arg=%d | stack=[%s] locals=[%s]%n", pos, name, arg, formatStack(stack), formatLocals(locals));
                    stack.push(0);
                    size = 2;
                } else if (op == 0xA7 || op == 0x99 || op == 0x9A || op == 0x9B || op == 0x9C || op == 0x9D || op == 0x9E || op == 0xC6 || op == 0xC7) {
                    int off = (short)((code[pos+1]<<8)|(code[pos+2]&0xFF));
                    System.out.printf("  %3d: %-15s off=%d | stack=[%s] locals=[%s]%n", pos, name, off, formatStack(stack), formatLocals(locals));
                    stack.pop();
                    size = 3;
                } else if (op == 0xBC) {
                    int arg = code[pos+1] & 0xFF;
                    System.out.printf("  %3d: %-15s arg=%d | stack=[%s] locals=[%s]%n", pos, name, arg, formatStack(stack), formatLocals(locals));
                    // newarray: pops 1 int, pushes 1 array ref
                    stack.pop();
                    stack.push(5); // array type
                    size = 2;
                } else if ((op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3A) || op == 0x2E || op == 0x2F || op == 0x30 || op == 0x31 || op == 0x32 || op == 0x4F || op == 0x50 || op == 0x51 || op == 0x52 || op == 0x53) {
                    int arg = code[pos+1] & 0xFF;
                    System.out.printf("  %3d: %-15s arg=%d | stack=[%s] locals=[%s]%n", pos, name, arg, formatStack(stack), formatLocals(locals));
                    if (op >= 0x15 && op <= 0x19) {
                        stack.push(locals[arg]);
                    } else if (op >= 0x36 && op <= 0x3A) {
                        locals[arg] = stack.pop();
                    } else if (op == 0x2E || op == 0x4F) {
                        // iaload/iastore: pops [array, index, value], pushes int
                        stack.pop(); stack.pop();
                        if (op == 0x2E) stack.push(0);
                    } else if (op == 0x53) {
                        stack.pop(); stack.pop(); stack.pop();
                    }
                    size = 2;
                } else if (op == 0x59 || op == 0x5F || op == 0x57 || op == 0x58) {
                    System.out.printf("  %3d: %-15s | stack=[%s] locals=[%s]%n", pos, name, formatStack(stack), formatLocals(locals));
                    if (op == 0x59) stack.push(stack.peek());
                    else if (op == 0x5F) { int a = stack.pop(); int b = stack.pop(); stack.push(a); stack.push(b); }
                    else if (op == 0x57) stack.pop();
                    else stack.pop(); stack.pop();
                    size = 1;
                } else if (op == 0x60 || op == 0x62 || op == 0x64 || op == 0x65) {
                    System.out.printf("  %3d: %-15s | stack=[%s] locals=[%s]%n", pos, name, formatStack(stack), formatLocals(locals));
                    stack.pop(); stack.pop(); stack.push(0);
                    size = 1;
                } else if (op == 0xAC || op == 0xB1) {
                    System.out.printf("  %3d: %-15s | stack=[%s] locals=[%s]%n", pos, name, formatStack(stack), formatLocals(locals));
                    size = 1;
                } else if (op == 0x01 || op == 0x02 || op == 0x03 || op == 0x04 || op == 0x05 || op == 0x06 || op == 0x07 || op == 0x08) {
                    System.out.printf("  %3d: %-15s | stack=[%s] locals=[%s]%n", pos, name, formatStack(stack), formatLocals(locals));
                    stack.push(0);
                    size = 1;
                } else {
                    System.out.printf("  %3d: %-15s (unknown) | stack=[%s] locals=[%s]%n", pos, name, formatStack(stack), formatLocals(locals));
                    size = 1;
                }
            } catch (Exception e) {
                System.out.printf("  %3d: ERROR %s | stack=[%s]%n", pos, e.getMessage(), formatStack(stack));
                break;
            }
            pos += size;
        }
    }
    
    static String formatStack(Deque<Integer> stack) {
        List<Integer> list = new ArrayList<>(stack);
        Collections.reverse(list);
        return list.toString();
    }
    
    static String formatLocals(int[] locals) {
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
