import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ClassFileParser {
    public static void main(String[] args) throws Exception {
        String[] files = {"target/lemonc/StructArrayDemo.class", "MinimalVerifyTest.class"};
        for (String f : files) {
            System.out.println("\n========== " + f + " ==========");
            byte[] data = Files.readAllBytes(Paths.get(f));
            parseClass(data);
        }
    }
    
    static void parseClass(byte[] data) throws Exception {
        // Magic
        int magic = ((data[0]&0xFF)<<24)|((data[1]&0xFF)<<16)|((data[2]&0xFF)<<8)|(data[3]&0xFF);
        System.out.println("Magic: 0x" + Integer.toHexString(magic));
        int minor = ((data[4]&0xFF)<<8)|(data[5]&0xFF);
        int major = ((data[6]&0xFF)<<8)|(data[7]&0xFF);
        System.out.println("Version: " + major + "." + minor);
        
        // Constant pool (entry 0 is reserved/dummy)
        int idx = 8;
        int cpCountRaw = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        // cpCountRaw is the COUNT, entries are indexed 1..cpCountRaw-1 (with gaps for long/double)
        // Actually: array of cpCountRaw entries, but entry 0 is unused and long/double consume 2
        System.out.println("CP count field: " + cpCountRaw);
        
        Object[] cp = new Object[cpCountRaw]; // indices 0..cpCountRaw-1
        // Parse until we've consumed cpCountRaw-1 actual entries
        int parsedCount = 0;
        while (idx < data.length && parsedCount < cpCountRaw - 1) {
            int start = idx;
            int tag = data[idx] & 0xFF;
            switch (tag) {
                case 1: { // Utf8
                    int len = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    String str = new String(data, idx+3, len);
                    cp[start] = str;
                    idx += 3 + len;
                    break;
                }
                case 3: { // Integer
                    int val = ((data[idx+1]&0xFF)<<24)|((data[idx+2]&0xFF)<<16)|((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp[start] = val;
                    idx += 5;
                    break;
                }
                case 4: { // Float
                    int bits = ((data[idx+1]&0xFF)<<24)|((data[idx+2]&0xFF)<<16)|((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp[start] = Float.intBitsToFloat(bits);
                    idx += 5;
                    break;
                }
                case 5: { // Long
                    long val = (((long)data[idx+1]&0xFF)<<56)|(((long)data[idx+2]&0xFF)<<48)|(((long)data[idx+3]&0xFF)<<40)|(((long)data[idx+4]&0xFF)<<32)|
                               (((long)data[idx+5]&0xFF)<<24)|(((long)data[idx+6]&0xFF)<<16)|(((long)data[idx+7]&0xFF)<<8)|(data[idx+8]&0xFF);
                    cp[start] = val;
                    if (start + 1 < cp.length) cp[start+1] = null; // long takes 2 slots
                    idx += 9;
                    parsedCount++;
                    break;
                }
                case 6: { // Double
                    long bits = (((long)data[idx+1]&0xFF)<<56)|(((long)data[idx+2]&0xFF)<<48)|(((long)data[idx+3]&0xFF)<<40)|(((long)data[idx+4]&0xFF)<<32)|
                                (((long)data[idx+5]&0xFF)<<24)|(((long)data[idx+6]&0xFF)<<16)|(((long)data[idx+7]&0xFF)<<8)|(data[idx+8]&0xFF);
                    cp[start] = Double.longBitsToDouble(bits);
                    if (start + 1 < cp.length) cp[start+1] = null; // double takes 2 slots
                    idx += 9;
                    parsedCount++;
                    break;
                }
                case 7: { // Class
                    int nameIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    cp[start] = "CLASS#" + nameIdx;
                    idx += 3;
                    break;
                }
                case 8: { // String
                    int strIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    cp[start] = "STRING#" + strIdx;
                    idx += 3;
                    break;
                }
                case 9: { // Fieldref
                    int classIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int natIdx = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp[start] = "FIELD#" + classIdx + "." + natIdx;
                    idx += 5;
                    break;
                }
                case 10: { // Methodref
                    int classIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int natIdx = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp[start] = "METHOD#" + classIdx + "." + natIdx;
                    idx += 5;
                    break;
                }
                case 11: { // InterfaceMethodref
                    int classIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int natIdx = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp[start] = "IMETHOD#" + classIdx + "." + natIdx;
                    idx += 5;
                    break;
                }
                case 12: { // NameAndType
                    int nameIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int descIdx = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp[start] = "NAT#" + nameIdx + ":" + descIdx;
                    idx += 5;
                    break;
                }
                case 15: { // InvokeDynamic
                    int bootIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int natIdx = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp[start] = "DYN#" + bootIdx + "." + natIdx;
                    idx += 5;
                    break;
                }
                case 16: { // MethodHandle
                    int kind = data[idx+1] & 0xFF;
                    int ref = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    cp[start] = "MH(" + kind + "," + ref + ")";
                    idx += 4;
                    break;
                }
                case 17: { // MethodType
                    int descIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    cp[start] = "MT#" + descIdx;
                    idx += 3;
                    break;
                }
                case 18: { // Dynamic
                    int cpIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int natIdx = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp[start] = "DYN#" + cpIdx + ":" + natIdx;
                    idx += 5;
                    break;
                }
                default:
                    System.err.println("Unknown CP tag " + tag + " at offset " + idx);
                    idx++;
                    parsedCount++;
                    break;
            }
        }
        System.out.println("Parsed " + parsedCount + " CP entries, idx=" + idx);
        
        // Print CP
        for (int i = 1; i < cp.length; i++) {
            if (cp[i] != null) {
                System.out.printf("  #%d: %s%n", i, cp[i]);
            }
        }
        
        // Find methods
        idx = 8;
        int cpCount2 = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        int pc2 = 0;
        while (idx < data.length && pc2 < cpCount2 - 1) {
            int tag = data[idx] & 0xFF;
            if (tag == 1) { int l = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF); idx += 3+l; }
            else if (tag == 3 || tag == 4) idx += 5;
            else if (tag == 5 || tag == 6) { idx += 9; pc2++; }
            else if (tag == 7 || tag == 8 || tag == 12 || tag == 15 || tag == 16 || tag == 17 || tag == 18) {
                if (tag == 16) idx += 4; else idx += 3;
                if (tag == 12 || tag == 15) idx += 2;
            }
            else if (tag == 9 || tag == 10 || tag == 11) idx += 5;
            else idx++;
            pc2++;
        }
        
        // Skip to methods
        int access = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        int thisClass = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        int superClass = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        
        System.out.println("\nThis class: " + cp[thisClass]);
        System.out.println("Super class: " + cp[superClass]);
        
        int ifaceCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2 + ifaceCount * 2;
        
        int fieldCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        for (int i = 0; i < fieldCount; i++) {
            int fa = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            int fname = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
            int fdesc = ((data[idx+4]&0xFF)<<8)|(data[idx+5]&0xFF);
            System.out.printf("  Field: #%d (%s) %s%n", fname, cp[fname], cp[fdesc]);
            idx += 6;
            int fattrCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            idx += 2;
            for (int j = 0; j < fattrCount; j++) {
                int aNameLen = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4 + aNameLen;
                int aLen = ((data[idx]&0xFF)<<24)|((data[idx+1]&0xFF)<<16)|((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4 + aLen;
            }
        }
        
        int methodCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        for (int i = 0; i < methodCount; i++) {
            int ma = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            int mname = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
            int mdesc = ((data[idx+4]&0xFF)<<8)|(data[idx+5]&0xFF);
            idx += 6;
            int mattrCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            idx += 2;
            
            System.out.printf("%n  Method: #%d (#%d) access=0x%x%n", mname, mdesc, ma);
            
            if (cp[mname].equals("main") && cp[mdesc].equals("([Ljava/lang/String;)V")) {
                for (int ai = 0; ai < mattrCount; ai++) {
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
                        
                        System.out.println("max_stack=" + maxStack + " max_locals=" + maxLocals + " code_len=" + codeLen);
                        
                        // Decode and print bytecode
                        decodeBytecode(code, cp);
                        
                        idx += codeLen;
                    }
                    idx += aLen;
                }
            } else {
                for (int ai = 0; ai < mattrCount; ai++) {
                    int aNameLen = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    idx += 4 + aNameLen;
                    int aLen = ((data[idx]&0xFF)<<24)|((data[idx+1]&0xFF)<<16)|((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    idx += 4 + aLen;
                }
            }
        }
    }
    
    static void decodeBytecode(byte[] code, Object[] cp) {
        // Map pool indices to descriptions
        Map<Integer, String> cpDesc = new HashMap<>();
        for (int i = 1; i < cp.length; i++) {
            if (cp[i] != null) cpDesc.put(i, cp[i].toString());
        }
        
        // Disassemble
        int pos = 0;
        while (pos < code.length) {
            int op = code[pos] & 0xFF;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%3d: ", pos));
            
            int size;
            switch (op) {
                case 0x12: // ldc (u1 index)
                    int c1 = code[pos+1] & 0xFF;
                    sb.append(String.format("ldc #%d [%s]", c1, cpDesc.get(c1)));
                    size = 2;
                    break;
                case 0x13: // ldc_w (u2 index)
                case 0x14: // ldc2_w
                    int c2 = ((code[pos+1]&0xFF)<<8)|(code[pos+2]&0xFF);
                    sb.append(String.format("ldc_w #%d [%s]", c2, cpDesc.get(c2)));
                    size = 3;
                    break;
                case 0x15: case 0x16: case 0x17: case 0x18: case 0x19: // iload..aload
                    sb.append(String.format("iload %d", code[pos+1] & 0xFF));
                    size = 2;
                    break;
                case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: // istore..astore
                    sb.append(String.format("istore %d", code[pos+1] & 0xFF));
                    size = 2;
                    break;
                case 0x2E: case 0x2F: case 0x30: case 0x31: case 0x32: // iaload..aaload
                    sb.append(String.format("iaload"));
                    size = 1;
                    break;
                case 0x4F: case 0x50: case 0x51: case 0x52: case 0x53: // iastore..aastore
                    sb.append(String.format("iastore"));
                    size = 1;
                    break;
                case 0xB4: case 0xB5: case 0xBB: case 0xB6: case 0xB7: case 0xB8: case 0xBD:
                    int cpIdx = ((code[pos+1]&0xFF)<<8)|(code[pos+2]&0xFF);
                    String opName;
                    switch(op) {
                        case 0xB4: opName = "getfield"; break;
                        case 0xB5: opName = "putfield"; break;
                        case 0xBB: opName = "new"; break;
                        case 0xB6: opName = "invokevirtual"; break;
                        case 0xB7: opName = "invokespecial"; break;
                        case 0xB8: opName = "invokestatic"; break;
                        case 0xBD: opName = "anewarray"; break;
                        default: opName = "unknown";
                    }
                    sb.append(String.format("%s #%d [%s]", opName, cpIdx, cpDesc.get(cpIdx)));
                    size = 3;
                    break;
                case 0xBC: // newarray
                    sb.append(String.format("newarray %d", code[pos+1] & 0xFF));
                    size = 2;
                    break;
                case 0xA7: // goto
                    int off = (short)((code[pos+1]<<8)|(code[pos+2]&0xFF));
                    sb.append(String.format("goto +%d", off));
                    size = 3;
                    break;
                case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E:
                    String ifName;
                    switch(op) {
                        case 0x99: ifName = "ifeq"; break;
                        case 0x9A: ifName = "ifne"; break;
                        case 0x9B: ifName = "iflt"; break;
                        case 0x9C: ifName = "ifge"; break;
                        case 0x9D: ifName = "ifgt"; break;
                        default: ifName = "ifle";
                    }
                    int off2 = (short)((code[pos+1]<<8)|(code[pos+2]&0xFF));
                    sb.append(String.format("%s +%d", ifName, off2));
                    size = 3;
                    break;
                case 0xC6: case 0xC7: // ifnull, ifnonnull
                    String condName = op == 0xC6 ? "ifnull" : "ifnonnull";
                    int off3 = (short)((code[pos+1]<<8)|(code[pos+2]&0xFF));
                    sb.append(String.format("%s +%d", condName, off3));
                    size = 3;
                    break;
                case 0xB1: sb.append("return"); size = 1; break;
                case 0xAC: sb.append("ireturn"); size = 1; break;
                case 0x59: sb.append("dup"); size = 1; break;
                case 0x5F: sb.append("swap"); size = 1; break;
                case 0x57: sb.append("pop"); size = 1; break;
                case 0x58: sb.append("pop2"); size = 1; break;
                case 0x01: sb.append("aconst_null"); size = 1; break;
                case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08:
                    sb.append(String.format("iconst_%d", op - 0x02));
                    size = 1;
                    break;
                case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65:
                    String arithName;
                    switch(op) {
                        case 0x60: arithName = "iadd"; break;
                        case 0x61: arithName = "isub"; break;
                        case 0x62: arithName = "fadd"; break;
                        case 0x63: arithName = "fsub"; break;
                        case 0x64: arithName = "ladd"; break;
                        default: arithName = "dadd";
                    }
                    sb.append(arithName);
                    size = 1;
                    break;
                case 0x66: case 0x67: case 0x68: case 0x69: case 0x6A: case 0x6B: case 0x6C: case 0x6D:
                case 0x6E: case 0x6F: case 0x70: case 0x71: case 0x72: case 0x73: case 0x74:
                case 0x75: case 0x76: case 0x77: case 0x78: case 0x79: case 0x7A: case 0x7B:
                case 0x7C: case 0x7D: case 0x7E: case 0x7F: case 0x80:
                    sb.append(String.format("0x%02x", op));
                    size = 1;
                    break;
                default:
                    sb.append(String.format("0x%02x", op));
                    size = 1;
                    break;
            }
            System.out.println(sb);
            pos += size;
        }
    }
}
