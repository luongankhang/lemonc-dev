import java.io.*;
import java.nio.file.*;
import java.util.*;

public class BytecodeDebug {
    public static void main(String[] args) throws Exception {
        String[] files = {"target/lemonc/StructArrayDemo.class", "MinimalVerifyTest.class"};
        for (String f : files) {
            System.out.println("=== " + f + " ===");
            byte[] data = Files.readAllBytes(Paths.get(f));
            System.out.println("Size: " + data.length);
            
            // Parse constant pool
            int idx = 8;
            int cpCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
            System.out.println("CP count: " + cpCount);
            
            Map<Integer, String> cpEntries = new HashMap<>();
            while (idx < data.length && cpCount > 1) {
                int tag = data[idx] & 0xFF;
                int start = idx;
                if (tag == 7) { // Class
                    int nameIdx = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    cpEntries.put(idx, "Class#" + nameIdx);
                    idx += 3;
                } else if (tag == 9 || tag == 10) { // Fieldref/Methodref
                    int classIdx = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    int natIdx = ((data[idx+3] & 0xFF) << 8) | (data[idx+4] & 0xFF);
                    cpEntries.put(idx, tag==9 ? "Field#" : "Method#" + classIdx + "." + natIdx);
                    idx += 5;
                } else if (tag == 12) { // NameAndType
                    int nameIdx = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    int descIdx = ((data[idx+3] & 0xFF) << 8) | (data[idx+4] & 0xFF);
                    cpEntries.put(idx, "NAT(" + nameIdx + "," + descIdx + ")");
                    idx += 5;
                } else if (tag == 3 || tag == 4 || tag == 8 || tag == 16 || tag == 17 || tag == 18) {
                    int len = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    byte[] payload = Arrays.copyOfRange(data, idx+3, idx+3+len);
                    String s = tag==18 ? new String(payload) : Arrays.toString(payload);
                    cpEntries.put(idx, tag==3 ? "Int:" + new String(new byte[]{payload[0],payload[1],payload[2],payload[3]}) : "Utf8:" + s);
                    idx += 3 + len;
                    if (tag == 3 || tag == 4) idx += 4;
                    if (tag == 4) idx += 4;
                } else {
                    System.err.println("Unknown tag " + tag + " at " + idx);
                    idx++;
                }
            }
            
            // Find methods
            int minor = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            int major = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            System.out.println("Java version: " + major + "." + minor);
            
            idx = 8;
            while (idx < data.length && (cpCount-- > 1)) {
                int tag = data[idx] & 0xFF;
                if (tag == 7) idx += 3;
                else if (tag == 9 || tag == 10) idx += 5;
                else if (tag == 12) idx += 5;
                else if (tag == 3 || tag == 4 || tag == 8 || tag == 16 || tag == 17 || tag == 18) {
                    int len = ((data[idx+1] & 0xFF) << 8) | (data[idx+2] & 0xFF);
                    idx += 3 + len;
                    if (tag == 3 || tag == 4) idx += 4;
                    if (tag == 4) idx += 4;
                } else idx++;
            }
            
            int fieldCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
            idx += 2 + fieldCount * 8;
            int methodCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
            idx += 2;
            
            for (int mi = 0; mi < methodCount; mi++) {
                int nameLen = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                String name = new String(data, idx+4, nameLen);
                idx += 4 + nameLen;
                int descLen = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
                String desc = new String(data, idx+2, descLen);
                idx += 2 + descLen;
                int attrCount = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
                idx += 2;
                
                if (name.equals("main") || name.equals("<init>")) {
                    System.out.println("\n--- Method: " + name + desc + " ---");
                    for (int ai = 0; ai < attrCount; ai++) {
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
                            idx += codeLen;
                            
                            System.out.println("max_stack=" + maxStack + " max_locals=" + maxLocals + " code_len=" + codeLen);
                            decodeBytecode(code, cpEntries);
                        } else {
                            idx += aLen;
                        }
                    }
                } else {
                    // Skip attributes
                    for (int ai = 0; ai < attrCount; ai++) {
                        int aNameLen = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                        idx += 4 + aNameLen;
                        int aLen = ((data[idx] & 0xFF) << 24) | ((data[idx+1] & 0xFF) << 16) | ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
                        idx += 4 + aLen;
                    }
                }
            }
        }
    }
    
    static void decodeBytecode(byte[] code, Map<Integer, String> cp) {
        int pos = 0;
        int[] opcodes = {0x12,0x13,0x14,0x15,0x16,0x17,0x18,0x19,0x36,0x37,0x38,0x39,0x3A,0x4F,0x2E,0xB4,0xB5,0xBB,0xBC,0xA7,0xB1,0xB7,0x59,0x5F,0xAC,0x60};
        String[] names = {"ldc","ldc_w","ldc2_w","iload","lload","fload","aload","istore","lstore","fstore","astore","iastore","iaload","getfield","putfield","new","newarray","goto","return","invokespecial","dup","swap","ireturn","iadd"};
        
        Map<Integer, String> opMap = new HashMap<>();
        for (int i = 0; i < opcodes.length; i++) opMap.put(opcodes[i], names[i]);
        
        while (pos < code.length) {
            int op = code[pos] & 0xFF;
            String opName = opMap.get(op);
            if (opName == null) opName = "0x" + Integer.toHexString(op);
            
            int size;
            if (op == 0x13 || op == 0x14 || op == 0xB4 || op == 0xB5 || op == 0xBB || op == 0xB7) {
                int arg = ((code[pos+1] & 0xFF) << 8) | (code[pos+2] & 0xFF);
                System.out.printf("  %3d: %-15s #%d%n", pos, opName, arg);
                size = 3;
            } else if (op == 0x12) {
                int arg = code[pos+1] & 0xFF;
                System.out.printf("  %3d: %-15s #%d%n", pos, opName, arg);
                size = 2;
            } else if (op == 0xA7) {
                int off = (short)((code[pos+1] << 8) | (code[pos+2] & 0xFF));
                System.out.printf("  %3d: %-15s +%d%n", pos, opName, off);
                size = 3;
            } else if (op == 0xBC) {
                int arg = code[pos+1] & 0xFF;
                System.out.printf("  %3d: %-15s %d%n", pos, opName, arg);
                size = 2;
            } else if ((op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3A) || op == 0x2E) {
                int arg = code[pos+1] & 0xFF;
                System.out.printf("  %3d: %-15s %d%n", pos, opName, arg);
                size = 2;
            } else if (op == 0x4F) {
                int arg = code[pos+1] & 0xFF;
                System.out.printf("  %3d: %-15s %d%n", pos, opName, arg);
                size = 2;
            } else {
                System.out.printf("  %3d: %-15s%n", pos, opName);
                size = 1;
            }
            pos += size;
        }
    }
}
