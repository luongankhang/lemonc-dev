import java.io.*;
import java.nio.file.*;
import java.util.*;

public class BytecodeCompare {
    public static void main(String[] args) throws Exception {
        // Compare StructArrayDemo vs a working equivalent
        byte[] bad = Files.readAllBytes(Paths.get("target/lemonc/StructArrayDemo.class"));
        byte[] good = Files.readAllBytes(Paths.get("MinimalVerifyTest.class"));
        
        System.out.println("Bad file size: " + bad.length);
        System.out.println("Good file size: " + good.length);
        
        // Dump raw hex for first few hundred bytes of each
        System.out.println("\n=== BAD (StructArrayDemo) ===");
        dumpHex(bad, 0, Math.min(200, bad.length));
        
        System.out.println("\n=== GOOD (MinimalVerifyTest) ===");
        dumpHex(good, 0, Math.min(200, good.length));
        
        // Find Code attribute in both
        System.out.println("\n=== BAD CODE ATTR ===");
        findCode(bad);
        
        System.out.println("\n=== GOOD CODE ATTR ===");
        findCode(good);
    }
    
    static void dumpHex(byte[] data, int start, int len) {
        for (int i = start; i < start + len && i < data.length; i += 16) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%04x: ", i));
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                sb.append(String.format("%02x ", data[i+j] & 0xFF));
            }
            sb.append("  ");
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                char c = (char)(data[i+j] & 0xFF);
                sb.append(c >= 32 && c < 127 ? c : '.');
            }
            System.out.println(sb);
        }
    }
    
    static void findCode(byte[] data) throws Exception {
        int idx = 8;
        // Skip CP
        int cpCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        for (int i = 0; i < cpCount && idx < data.length; i++) {
            int tag = data[idx] & 0xFF;
            if (tag == 1) { int l = ((data[idx+1]&0xFF)<<8)|(data[idx+2]&0xFF); idx += 3+l; }
            else if (tag == 3 || tag == 4) idx += 5;
            else if (tag == 5 || tag == 6) idx += 9;
            else if (tag == 7 || tag == 8) idx += 3;
            else if (tag == 9 || tag == 10 || tag == 12) idx += 5;
            else idx++;
        }
        
        idx += 2; // access flags
        idx += 2; // this class
        idx += 2; // super class
        idx += 2; // interfaces count
        int ifaceCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2 + ifaceCount * 2;
        
        // Fields
        int fieldCount = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
        idx += 2;
        for (int i = 0; i < fieldCount; i++) {
            idx += 8; // access + name + desc + attrs
            int attrCount = ((data[idx-4]&0xFF)<<8)|(data[idx-3]&0xFF);
            for (int j = 0; j < attrCount; j++) {
                int aLen = ((data[idx]&0xFF)<<24)|((data[idx+1]&0xFF)<<16)|((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4 + aLen;
            }
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
                int aLen = ((data[idx]&0xFF)<<24)|((data[idx+1]&0xFF)<<16)|((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                idx += 4;
                if (aname == 1 && mname == 1) { // Code
                    int ms = ((data[idx]&0xFF)<<8)|(data[idx+1]&0xFF);
                    int ml = ((data[idx+2]&0xFF)<<8)|(data[idx+3]&0xFF);
                    int clen = ((data[idx+4]&0xFF)<<24)|((data[idx+5]&0xFF)<<16)|((data[idx+6]&0xFF)<<8)|(data[idx+7]&0xFF);
                    System.out.println("max_stack=" + ms + " max_locals=" + ml + " code_len=" + clen);
                    // Dump first 100 bytes of code
                    dumpHex(data, idx, Math.min(100, clen));
                }
                idx += aLen;
            }
            idx += 2; // access
            idx += 2; // name
            idx += 2; // desc
        }
    }
}
