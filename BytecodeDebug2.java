import java.io.*;
import java.nio.file.*;
import java.util.*;

public class BytecodeDebug2 {
    public static void main(String[] args) throws Exception {
        String[] files = {"target/lemonc/StructArrayDemo.class", "MinimalVerifyTest.class"};
        for (String f : files) {
            System.out.println("=== " + f + " ===");
            byte[] data = Files.readAllBytes(Paths.get(f));
            dumpClass(data);
        }
    }
    
    static void dumpClass(byte[] data) throws Exception {
        // Magic
        int magic = ((data[0]&0xFF)<<24)|((data[1]&0xFF)<<16)|((data[2]&0xFF)<<8)|(data[3]&0xFF);
        System.out.println("Magic: 0x" + Integer.toHexString(magic));
        int minor = ((data[4]&0xFF)<<8)|(data[5]&0xFF);
        int major = ((data[6]&0xFF)<<8)|(data[7]&0xFF);
        System.out.println("Version: " + major + "." + minor);
        
        int idx = 8;
        int cpCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        System.out.println("CP count: " + cpCount);
        
        // Parse constant pool
        Map<Integer, String> cp = new HashMap<>();
        while (idx < data.length && cpCount > 1) {
            int tag = data[idx] & 0xFF;
            int start = idx;
            switch(tag) {
                case 1: // Utf8
                    int uLen = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    String uStr = new String(data, idx+3, uLen);
                    cp.put(start, "Utf8(\"" + uStr + "\")");
                    idx += 3 + uLen;
                    break;
                case 3: // Integer
                    int iVal = ((data[idx+1]&0xFF)<<24)|((data[idx+2]&0xFF)<<16)|((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp.put(start, "Int(" + iVal + ")");
                    idx += 5;
                    break;
                case 4: // Float
                    int fBits = ((data[idx+1]&0xFF)<<24)|((data[idx+2]&0xFF)<<16)|((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp.put(start, "Float(" + Float.intBitsToFloat(fBits) + ")");
                    idx += 5;
                    break;
                case 5: // Long
                    long lVal = (((long)data[idx+1]&0xFF)<<56)|(((long)data[idx+2]&0xFF)<<48)|(((long)data[idx+3]&0xFF)<<40)|(((long)data[idx+4]&0xFF)<<32)|(((long)data[idx+5]&0xFF)<<24)|(((long)data[idx+6]&0xFF)<<16)|(((long)data[idx+7]&0xFF)<<8)|(data[idx+8]&0xFF);
                    cp.put(start, "Long(" + lVal + ")");
                    cp.put(start+1, null); // Long takes 2 slots
                    idx += 9;
                    break;
                case 6: // Double
                    long dBits = (((long)data[idx+1]&0xFF)<<56)|(((long)data[idx+2]&0xFF)<<48)|(((long)data[idx+3]&0xFF)<<40)|(((long)data[idx+4]&0xFF)<<32)|(((long)data[idx+5]&0xFF)<<24)|(((long)data[idx+6]&0xFF)<<16)|(((long)data[idx+7]&0xFF)<<8)|(data[idx+8]&0xFF);
                    cp.put(start, "Double(" + Double.longBitsToDouble(dBits) + ")");
                    cp.put(start+1, null);
                    idx += 9;
                    break;
                case 7: // Class
                    int cIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    cp.put(start, "Class#" + cIdx);
                    idx += 3;
                    break;
                case 8: // String
                    int sIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    cp.put(start, "String#" + sIdx);
                    idx += 3;
                    break;
                case 9: // Fieldref
                    int fClass = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int fNat = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp.put(start, "Field#" + fClass + "." + fNat);
                    idx += 5;
                    break;
                case 10: // Methodref
                    int mClass = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int mNat = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp.put(start, "Method#" + mClass + "." + mNat);
                    idx += 5;
                    break;
                case 11: // InterfaceMethodref
                    int imClass = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int imNat = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp.put(start, "IFaceMeth#" + imClass + "." + imNat);
                    idx += 5;
                    break;
                case 12: // NameAndType
                    int nName = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int nDesc = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp.put(start, "NAT#" + nName + ":" + nDesc);
                    idx += 5;
                    break;
                case 15: // InvokeDynamic
                    int bdIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int bNameIdx = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp.put(start, "Bootstrap#" + bdIdx + ":" + bNameIdx);
                    idx += 5;
                    break;
                case 16: // MethodHandle
                    int mhKind = data[idx+1] & 0xFF;
                    int mhRef = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    cp.put(start, "MH(" + mhKind + "," + mhRef + ")");
                    idx += 4;
                    break;
                case 17: // MethodType
                    int mtDesc = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    cp.put(start, "MT#" + mtDesc);
                    idx += 3;
                    break;
                case 18: // Dynamic
                    int dIdx = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF);
                    int dNatIdx = ((data[idx+3]&0xFF)<<8)|(data[idx+4]&0xFF);
                    cp.put(start, "Dyn#" + dIdx + ":" + dNatIdx);
                    idx += 5;
                    break;
                default:
                    System.err.println("Unknown CP tag " + tag + " at " + idx);
                    idx++;
                    break;
            }
        }
        
        // Print CP
        for (Map.Entry<Integer, String> e : cp.entrySet()) {
            if (e.getValue() != null) {
                System.out.printf("  #%d: %s%n", e.getKey(), e.getValue());
            }
        }
        
        // Fields
        idx = 8;
        while (idx < data.length && cpCount > 1) {
            int tag = data[idx] & 0xFF;
            if (tag == 1) { int l = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF); idx += 3+l; }
            else if (tag == 3) idx += 5;
            else if (tag == 4) idx += 5;
            else if (tag == 5) { idx += 9; cpCount--; } // already decremented
            else if (tag == 6) { idx += 9; cpCount--; }
            else if (tag == 7) idx += 3;
            else if (tag == 8) idx += 3;
            else if (tag == 9 || tag == 10) idx += 5;
            else if (tag == 12) idx += 5;
            else if (tag == 15) idx += 5;
            else if (tag == 16) idx += 4;
            else if (tag == 17) idx += 3;
            else if (tag == 18) idx += 5;
            else idx++;
            cpCount--;
        }
        
        int accessFlags = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        int thisClass = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        int superClass = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        
        System.out.println("Access: 0x" + Integer.toHexString(accessFlags));
        System.out.println("This class: #" + thisClass + " = " + cp.get(thisClass));
        System.out.println("Super class: #" + superClass + " = " + cp.get(superClass));
        
        // Skip interfaces
        int ifaceCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2 + ifaceCount * 2;
        
        // Fields
        int fieldCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        for (int i = 0; i < fieldCount; i++) {
            int fa = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            int fname = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
            int fdesc = ((data[idx+4]&0xFF)<<8)|(data[idx+5]&0xFF);
            int fattr = ((data[idx+6]&0xFF)<<8)|(data[idx+7]&0xFF);
            idx += 8 + fattr;
            System.out.printf("  Field: #%d %s [%s]%n", fname, cp.get(fname), cp.get(fdesc));
        }
        
        // Methods
        int methodCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        for (int i = 0; i < methodCount; i++) {
            int ma = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            int mname = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
            int mdesc = ((data[idx+4]&0xFF)<<8)|(data[idx+5]&0xFF);
            idx += 6;
            int mattrCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
            idx += 2;
            for (int j = 0; j < mattrCount; j++) {
                int aname = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4;
                int alen = ((data[idx]&0xFF)<<24)|((data[idx+1]&0xFF)<<16)|((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4;
                if (aname == 1 && mname == 1) { // Code attribute
                    System.out.println("\n--- " + cp.get(mname) + cp.get(mdesc) + " ---");
                    int ms = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
                    int ml = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    int clen = ((data[idx+4]&0xFF)<<24)|((data[idx+5]&0xFF)<<16)|((data[idx+6]&0xFF)<<8)|(data[idx+7]&0xFF);
                    idx += 8;
                    byte[] code = Arrays.copyOfRange(data, idx, idx + clen);
                    idx += clen;
                    System.out.println("max_stack=" + ms + " max_locals=" + ml + " code_len=" + clen);
                    decodeCode(code);
                } else {
                    idx += alen;
                }
            }
        }
    }
    
    static void decodeCode(byte[] code) {
        Map<Integer, String> opNames = new HashMap<>();
        opNames.put(0x12,"ldc"); opNames.put(0x13,"ldc_w"); opNames.put(0x14,"ldc2_w");
        opNames.put(0x15,"iload"); opNames.put(0x16,"lload"); opNames.put(0x17,"fload"); opNames.put(0x18,"dload"); opNames.put(0x19,"aload");
        opNames.put(0x36,"istore"); opNames.put(0x37,"lstore"); opNames.put(0x38,"fstore"); opNames.put(0x39,"dstore"); opNames.put(0x3A,"astore");
        opNames.put(0x4F,"iastore"); opNames.put(0x50,"lastore"); opNames.put(0x51,"fastore"); opNames.put(0x52,"dastore"); opNames.put(0x53,"aastore");
        opNames.put(0x2E,"iaload"); opNames.put(0x2F,"laload"); opNames.put(0x30,"faload"); opNames.put(0x31,"daload"); opNames.put(0x32,"aaload");
        opNames.put(0xB4,"getfield"); opNames.put(0xB5,"putfield");
        opNames.put(0xBB,"new"); opNames.put(0xBC,"newarray");
        opNames.put(0xA7,"goto"); opNames.put(0xB1,"return"); opNames.put(0xAC,"ireturn");
        opNames.put(0xB7,"invokespecial"); opNames.put(0xB6,"invokevirtual"); opNames.put(0xB8,"invokestatic");
        opNames.put(0x59,"dup"); opNames.put(0x5F,"swap"); opNames.put(0x57,"pop");
        opNames.put(0x60,"iadd"); opNames.put(0x62,"fadd"); opNames.put(0x64,"ladd"); opNames.put(0x65,"dadd");
        opNames.put(0x99,"if_eq"); opNames.put(0x9A,"if_ne"); opNames.put(0x9B,"if_lt"); opNames.put(0x9C,"if_ge");
        opNames.put(0x9D,"if_gt"); opNames.put(0x9E,"if_le");
        opNames.put(0xC6,"ifnull"); opNames.put(0xC7,"ifnonnull");
        opNames.put(0x94,"lcmp"); opNames.put(0x95,"fcmpl"); opNames.put(0x96,"fcmpg");
        opNames.put(0x97,"dcmpl"); opNames.put(0x98,"dcmpg");
        opNames.put(0xBD,"anewarray"); opNames.put(0xBE,"arraylength");
        opNames.put(0x5A,"dup_x1"); opNames.put(0x5B,"dup_x2");
        opNames.put(0xBF,"athrow");
        
        int pos = 0;
        while (pos < code.length) {
            int op = code[pos] & 0xFF;
            String name = opNames.get(op);
            if (name == null) name = "0x" + Integer.toHexString(op);
            
            int size;
            if (op == 0x13 || op == 0x14 || op == 0xB4 || op == 0xB5 || op == 0xBB || op == 0xB7 || op == 0xB6 || op == 0xB8 || op == 0xBD) {
                int arg = ((code[pos+1]&0xFF)<<8)|(code[pos+2]&0xFF);
                System.out.printf("  %3d: %-15s #%d%n", pos, name, arg);
                size = 3;
            } else if (op == 0x12) {
                int arg = code[pos+1] & 0xFF;
                System.out.printf("  %3d: %-15s #%d%n", pos, name, arg);
                size = 2;
            } else if (op == 0xA7 || op == 0x99 || op == 0x9A || op == 0x9B || op == 0x9C || op == 0x9D || op == 0x9E || op == 0xC6 || op == 0xC7) {
                int off = (short)((code[pos+1]<<8)|(code[pos+2]&0xFF));
                System.out.printf("  %3d: %-15s +%d%n", pos, name, off);
                size = 3;
            } else if (op == 0xBC || op == 0xBE) {
                int arg = code[pos+1] & 0xFF;
                System.out.printf("  %3d: %-15s %d%n", pos, name, arg);
                size = 2;
            } else if ((op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3A) || op == 0x2E || op == 0x2F || op == 0x30 || op == 0x31 || op == 0x32 || op == 0x4F || op == 0x50 || op == 0x51 || op == 0x52 || op == 0x53) {
                int arg = code[pos+1] & 0xFF;
                System.out.printf("  %3d: %-15s %d%n", pos, name, arg);
                size = 2;
            } else {
                System.out.printf("  %3d: %-15s%n", pos, name);
                size = 1;
            }
            pos += size;
        }
    }
}
