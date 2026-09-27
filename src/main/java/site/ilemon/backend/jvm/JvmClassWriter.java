package site.ilemon.backend.jvm;

import site.ilemon.exception.CompilerException;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JVM class-file writer.
 *
 * <p>Emits Java 5 (major version 49) class files, which the JVM verifies with
 * type inference and which require no StackMapTable, keeping the writer small.
 * Only the instruction vocabulary produced by {@link JvmInstructionEmitter} is
 * needed.</p>
 *
 * <p>This class lives exclusively in the JVM backend; LemonIR never sees it.</p>
 */
final class JvmClassWriter {

    // Constant pool tags
    private static final int TAG_UTF8 = 1;
    private static final int TAG_INTEGER = 3;
    private static final int TAG_FLOAT = 4;
    private static final int TAG_LONG = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_CLASS = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_FIELDREF = 9;
    private static final int TAG_METHODREF = 10;
    private static final int TAG_NAME_AND_TYPE = 12;

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_SUPER = 0x0020;

    /** Index 0 is reserved and unused. */
    private final List<byte[]> pool = new ArrayList<>();
    private final Map<String, Integer> utf8Indexes = new HashMap<>();
    private final Map<Integer, Integer> integerIndexes = new HashMap<>();
    private final Map<Float, Integer> floatIndexes = new HashMap<>();
    private final Map<Long, Integer> longIndexes = new HashMap<>();
    private final Map<Double, Integer> doubleIndexes = new HashMap<>();
    private final Map<String, Integer> classIndexes = new HashMap<>();
    private final Map<String, Integer> stringIndexes = new HashMap<>();
    private final Map<MemberRef, Integer> fieldRefIndexes = new HashMap<>();
    private final Map<MemberRef, Integer> methodRefIndexes = new HashMap<>();

    JvmClassWriter() {
        pool.add(null); // dummy entry at index 0
    }

    // ---------------------------------------------------------------- pool

    int utf8(String value) {
        Integer existing = utf8Indexes.get(value);
        if (existing != null) {
            return existing;
        }
        byte[] bytes = modifiedUtf8(value);
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(TAG_UTF8);
        writeU2(entry, bytes.length);
        entry.writeBytes(bytes);
        int index = add(entry.toByteArray());
        utf8Indexes.put(value, index);
        return index;
    }

    int integer(int value) {
        Integer existing = integerIndexes.get(value);
        if (existing != null) {
            return existing;
        }
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(TAG_INTEGER);
        writeU4(entry, value);
        int index = add(entry.toByteArray());
        integerIndexes.put(value, index);
        return index;
    }

    int floatConstant(float value) {
        Integer existing = floatIndexes.get(value);
        if (existing != null) {
            return existing;
        }
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(TAG_FLOAT);
        writeU4(entry, Float.floatToRawIntBits(value));
        int index = add(entry.toByteArray());
        floatIndexes.put(value, index);
        return index;
    }

    int longConstant(long value) {
        Long key = value;
        Integer existing = longIndexes.get(key);
        if (existing != null) {
            return existing;
        }
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(TAG_LONG);
        writeU8(entry, value);
        int index = add(entry.toByteArray());
        longIndexes.put(key, index);
        return index;
    }

    int doubleConstant(double value) {
        Double key = value;
        Integer existing = doubleIndexes.get(key);
        if (existing != null) {
            return existing;
        }
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(TAG_DOUBLE);
        writeU8(entry, Double.doubleToRawLongBits(value));
        int index = add(entry.toByteArray());
        doubleIndexes.put(key, index);
        return index;
    }

    int classRef(String internalName) {
        Integer existing = classIndexes.get(internalName);
        if (existing != null) {
            return existing;
        }
        int nameIndex = utf8(internalName);
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(TAG_CLASS);
        writeU2(entry, nameIndex);
        int index = add(entry.toByteArray());
        classIndexes.put(internalName, index);
        return index;
    }

    int stringRef(String value) {
        Integer existing = stringIndexes.get(value);
        if (existing != null) {
            return existing;
        }
        int valueIndex = utf8(value);
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(TAG_STRING);
        writeU2(entry, valueIndex);
        int index = add(entry.toByteArray());
        stringIndexes.put(value, index);
        return index;
    }

    int methodRef(String owner, String name, String descriptor) {
        return ref(methodRefIndexes, TAG_METHODREF, owner, name, descriptor);
    }

    int fieldRef(String owner, String name, String descriptor) {
        return ref(fieldRefIndexes, TAG_FIELDREF, owner, name, descriptor);
    }

    private int ref(Map<MemberRef, Integer> indexes, int tag, String owner, String name, String descriptor) {
        MemberRef key = new MemberRef(owner, name, descriptor);
        Integer existing = indexes.get(key);
        if (existing != null) {
            return existing;
        }
        int classIndex = classRef(owner);
        int nameAndTypeIndex = nameAndType(name, descriptor);
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(tag);
        writeU2(entry, classIndex);
        writeU2(entry, nameAndTypeIndex);
        int index = add(entry.toByteArray());
        indexes.put(key, index);
        return index;
    }

    private int nameAndType(String name, String descriptor) {
        int nameIndex = utf8(name);
        int descriptorIndex = utf8(descriptor);
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(TAG_NAME_AND_TYPE);
        writeU2(entry, nameIndex);
        writeU2(entry, descriptorIndex);
        return add(entry.toByteArray());
    }

    /** Long/double constants occupy two constant-pool indices. */
    private int add(byte[] entry) {
        pool.add(entry);
        int index = pool.size() - 1;
        if (entry[0] == TAG_LONG || entry[0] == TAG_DOUBLE) {
            pool.add(null);
        }
        return index;
    }

    /** Stack-slot width of the constant at a pool index (2 for long/double). */
    int constantSlots(int poolIndex) {
        byte[] entry = pool.get(poolIndex);
        if (entry == null) {
            return 0;
        }
        int tag = entry[0];
        if (tag == TAG_LONG || tag == TAG_DOUBLE) {
            return 2;
        }
        return 1;
    }

    // ------------------------------------------------------------- writing

    byte[] writeClass(IrModule module, List<JvmMethod> methods) {
        return writeClass(module.name(), methods, collectStructFields(module));
    }

    private byte[] writeClass(String className, List<JvmMethod> methods, List<StructField> instanceFields) {
        // Resolve class-level references before writing: they must be part of
        // the constant pool, and the pool is flushed before the access flags.
        int thisClassIndex = classRef(className);
        int superClassIndex = classRef("java/lang/Object");
        int codeAttributeIndex = utf8("Code");
        int[] methodNameIndexes = new int[methods.size()];
        int[] methodDescriptorIndexes = new int[methods.size()];
        for (int i = 0; i < methods.size(); i++) {
            methodNameIndexes[i] = utf8(methods.get(i).name());
            methodDescriptorIndexes[i] = utf8(methods.get(i).descriptor());
        }
        int[] fieldNameIndexes = new int[instanceFields.size()];
        int[] fieldDescriptorIndexes = new int[instanceFields.size()];
        for (int i = 0; i < instanceFields.size(); i++) {
            fieldNameIndexes[i] = utf8(instanceFields.get(i).name());
            fieldDescriptorIndexes[i] = utf8(instanceFields.get(i).descriptor());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU4(out, 0xCAFEBABE);
        writeU2(out, 0);              // minor version
        writeU2(out, 49);             // major version: Java 5 (no StackMapTable needed)
        writeU2(out, pool.size());    // constant pool count
        for (byte[] entry : pool) {
            if (entry != null) {
                out.writeBytes(entry);
            }
        }
        writeU2(out, ACC_PUBLIC | ACC_SUPER);
        writeU2(out, thisClassIndex);
        writeU2(out, superClassIndex);
        writeU2(out, 0);              // interfaces
        writeU2(out, instanceFields.size());
        for (int i = 0; i < instanceFields.size(); i++) {
            writeU2(out, ACC_PUBLIC);
            writeU2(out, fieldNameIndexes[i]);
            writeU2(out, fieldDescriptorIndexes[i]);
            writeU2(out, 0);          // field attributes
        }
        writeU2(out, methods.size()); // methods
        for (int i = 0; i < methods.size(); i++) {
            writeMethod(out, methods.get(i), methodNameIndexes[i], methodDescriptorIndexes[i], codeAttributeIndex);
        }
        writeU2(out, 0);              // class attributes
        return out.toByteArray();
    }

    // ------------------------------------------------------------ structs

    /** One synthesized struct instance field. */
    private record StructField(String structName, String name, String descriptor) {
    }

    // Opcodes used by the synthesized struct classes.
    private static final int OPCODE_ALOAD = 0x19;
    private static final int OPCODE_ASTORE = 0x3A; // astore n (with index)
    private static final int OPCODE_NEW = 0xBB;
    private static final int OPCODE_DUP = 0x59;
    private static final int OPCODE_SWAP = 0x5F;
    private static final int OPCODE_RETURN = 0xB1;
    private static final int OPCODE_GETFIELD = 0xB4;
    private static final int OPCODE_PUTFIELD = 0xB5;
    private static final int OPCODE_INVOKESPECIAL = 0xB7;

    /** Gathers every struct instance field referenced by any method's code. */
    private List<StructField> collectStructFields(IrModule module) {
        List<StructField> fields = new ArrayList<>();
        for (IrModule.IrStruct struct : module.structsView().values()) {
            for (IrModule.IrStructField field : struct.fields()) {
                fields.add(new StructField(struct.name(), field.name(),
                        structFieldDescriptor(field.type(), module.name())));
            }
        }
        return fields;
    }

    /** Descriptor mapper for struct layout generation (owner = this module). */
    private String structFieldDescriptor(IrType type, String owner) {
        JvmTypeMapper mapper = new JvmTypeMapper();
        mapper.setStructOwner(owner);
        return mapper.descriptor(type);
    }

    /**
     * Writes one synthesized class per declared struct: {@code Main$Point}.
     * Each has zero-initialized instance fields, a no-arg constructor, and a
     * copy constructor that clones nested struct fields recursively — the
     * JVM counterpart of C by-value struct assignment.
     */
    void writeStructClasses(IrModule module, Path outputDirectory) throws IOException {
        if (module.structsView().isEmpty()) {
            return;
        }
        for (IrModule.IrStruct struct : module.structsView().values()) {
            Files.write(outputDirectory.resolve(module.name() + "$" + struct.name() + ".class"),
                    structClassBytes(module, struct));
        }
    }

    private byte[] structClassBytes(IrModule module, IrModule.IrStruct struct) {
        String owner = module.name();
        String innerName = owner + "$" + struct.name();
        JvmClassWriter inner = new JvmClassWriter();
        JvmTypeMapper mapper = new JvmTypeMapper();
        mapper.setStructOwner(owner);

        // The nested class declares one instance field per struct field.
        List<StructField> instanceFields = new ArrayList<>();
        for (IrModule.IrStructField field : struct.fields()) {
            instanceFields.add(new StructField(struct.name(), field.name(),
                    mapper.descriptor(field.type())));
        }

        List<JvmMethod> innerMethods = new ArrayList<>();

        // no-arg constructor: <init>()V — call super, then allocate a zero
        // object for every nested struct field (primitives start at zero).
        JvmCodeBuilder zero = new JvmCodeBuilder();
        zero.label("entry");
        zero.load(OPCODE_ALOAD, 0);
        zero.invoke(OPCODE_INVOKESPECIAL, inner.methodRef("java/lang/Object", "<init>", "()V"), 0, 0);
        for (IrModule.IrStructField field : struct.fields()) {
            if (field.type().kind() == IrType.Kind.STRUCT) {
                String nested = owner + "$" + field.type().name();
                zero.load(OPCODE_ALOAD, 0);
                zero.cpRef(OPCODE_NEW, inner.classRef(nested));
                zero.simple(OPCODE_DUP);
                zero.invoke(OPCODE_INVOKESPECIAL, inner.methodRef(nested, "<init>", "()V"), 0, 0);
                zero.fieldAccess(OPCODE_PUTFIELD, inner.fieldRef(innerName, field.name(),
                        mapper.descriptor(field.type())), 1);
            }
        }
        zero.simple(OPCODE_RETURN);
        innerMethods.add(new JvmMethod(ACC_PUBLIC, "<init>", "()V", zero.toBytecode(), 3, 1));

        // copy constructor: <init>(LInner;)V — copy every field; nested struct
        // fields are cloned recursively through the nested copy constructor
        // (this.nested = new Nested(src.nested)), the by-value C semantics.
        JvmCodeBuilder copyCtor = new JvmCodeBuilder();
        copyCtor.label("entry");
        copyCtor.load(OPCODE_ALOAD, 0);
        copyCtor.invoke(OPCODE_INVOKESPECIAL, inner.methodRef("java/lang/Object", "<init>", "()V"), 0, 0);
        for (IrModule.IrStructField field : struct.fields()) {
            IrType fieldType = field.type();
            String desc = mapper.descriptor(fieldType);
            copyCtor.load(OPCODE_ALOAD, 0);
            if (fieldType.kind() == IrType.Kind.STRUCT) {
                // this.in = new Inner(src.in), the exact javac shape:
                // [this, n, n, src] → getfield → [this, n, src.in]
                // → <init> pops (n, src.in) → [this, n] → putfield → [].
                String nested = owner + "$" + fieldType.name();
                copyCtor.cpRef(OPCODE_NEW, inner.classRef(nested));
                copyCtor.simple(OPCODE_DUP);           // [this, n, n]
                copyCtor.load(OPCODE_ALOAD, 1);        // [this, n, n, src]
                copyCtor.fieldAccess(OPCODE_GETFIELD, inner.fieldRef(innerName, field.name(), desc), 1);
                copyCtor.invoke(OPCODE_INVOKESPECIAL, inner.methodRef(nested, "<init>",
                        "(L" + nested + ";)V"), 1, 0); // [this, n]
                copyCtor.fieldAccess(OPCODE_PUTFIELD, inner.fieldRef(innerName, field.name(), desc), 1);
                continue;
            }
            copyCtor.load(OPCODE_ALOAD, 1);
            copyCtor.fieldAccess(OPCODE_GETFIELD, inner.fieldRef(innerName, field.name(), desc),
                    mapper.slots(fieldType));
            copyCtor.fieldAccess(OPCODE_PUTFIELD, inner.fieldRef(innerName, field.name(), desc),
                    mapper.slots(fieldType));
        }
        copyCtor.simple(OPCODE_RETURN);
        innerMethods.add(new JvmMethod(ACC_PUBLIC, "<init>", "(L" + innerName + ";)V",
                copyCtor.toBytecode(), 5, 3)); // locals: this, src, scratch@2

        return inner.writeClass(innerName, innerMethods, instanceFields);
    }
    private void writeMethod(ByteArrayOutputStream out, JvmMethod method, int nameIndex, int descriptorIndex, int codeAttributeIndex) {
        writeU2(out, method.access());
        writeU2(out, nameIndex);
        writeU2(out, descriptorIndex);
        writeU2(out, 1); // attributes: Code
        writeU2(out, codeAttributeIndex);
        // Code attribute length: max_stack(2) + max_locals(2) + code_length(4)
        // + code + exception_table_length(2) + attributes_count(2)
        writeU4(out, 12 + method.code().length);
        writeU2(out, method.maxStack());
        writeU2(out, method.maxLocals());
        writeU4(out, method.code().length);
        out.writeBytes(method.code());
        writeU2(out, 0); // exception table
        writeU2(out, 0); // code attributes
    }

    /** Class-level access flags for the generated class. */
    static int classAccess() {
        return ACC_PUBLIC | ACC_SUPER;
    }

    /** Method access flags: main is public static; other methods are static. */
    static int methodAccess(boolean isMain) {
        return isMain ? ACC_PUBLIC | ACC_STATIC : ACC_STATIC;
    }

    private static byte[] modifiedUtf8(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) {
                out.write(0xC0);
                out.write(0x80);
            } else if (c < 0x80) {
                out.write(c);
            } else if (c < 0x800) {
                out.write(0xC0 | (c >> 6));
                out.write(0x80 | (c & 0x3F));
            } else {
                out.write(0xE0 | (c >> 12));
                out.write(0x80 | ((c >> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            }
        }
        return out.toByteArray();
    }

    private static void writeU2(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU4(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private static void writeU8(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xFF));
        }
    }

    private record MemberRef(String owner, String name, String descriptor) {
        private MemberRef {
            if (owner == null || name == null || descriptor == null) {
                throw new CompilerException("Invalid constant pool reference");
            }
        }
    }
}