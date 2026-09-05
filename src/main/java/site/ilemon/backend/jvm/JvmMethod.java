package site.ilemon.backend.jvm;

/** A fully lowered JVM method ready to be written into a class file. */
record JvmMethod(int access, String name, String descriptor, byte[] code,
                 int maxStack, int maxLocals) {
}