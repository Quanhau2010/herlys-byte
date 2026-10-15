package dev.herlysbyte.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Reads archive metadata and class structure. It never loads or executes classes from the archive. */
public final class JarAnalyzer {
    public JarAnalysis analyze(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Tệp không tồn tại hoặc không phải tệp thường.");
        if (!path.getFileName().toString().toLowerCase().endsWith(".jar")) throw new IOException("Chỉ hỗ trợ tệp .jar.");
        List<ClassInfo> classes = new ArrayList<>();
        List<JarResourceInfo> resourceEntries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> manifestData = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                attrs.forEach((k, v) -> manifestData.put(k.toString(), v.toString()));
                manifest.getEntries().forEach((name, values) -> values.forEach((k, v) -> manifestData.put(name + " / " + k, v.toString())));
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (entry.getName().endsWith(".class") && !entry.getName().equals("module-info.class")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        classes.add(readClass(in.readAllBytes()));
                    } catch (Exception e) {
                        warnings.add("Không đọc được " + entry.getName() + ": " + safeMessage(e));
                    }
                } else resourceEntries.add(new JarResourceInfo(entry.getName(), entry.getSize()));
            }
        } catch (IOException e) {
            throw new IOException("Không thể mở JAR: " + safeMessage(e), e);
        }
        classes.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return new JarAnalysis(path.toAbsolutePath(), Files.size(path), manifestData, classes, resourceEntries, warnings);
    }

    private ClassInfo readClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        final String[] name = {reader.getClassName()}, superName = {reader.getSuperName()};
        final int[] access = {reader.getAccess()}, fields = {0}, major = {reader.readUnsignedShort(6)};
        List<String> interfaces = new ArrayList<>(), methods = new ArrayList<>();
        List<String> strings = new ArrayList<>();
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        if (reader.getInterfaces() != null) for (String i : reader.getInterfaces()) interfaces.add(i.replace('/', '.'));
        if (superName[0] != null) dependencies.add(superName[0].replace('/', '.'));
        for (String i : reader.getInterfaces()) dependencies.add(i.replace('/', '.'));
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int acc, String n, String desc, String signature, Object value) {
                fields[0]++;
                collectType(Type.getType(desc), dependencies);
                if (value instanceof String s) strings.add(s);
                return null;
            }
            @Override public MethodVisitor visitMethod(int acc, String n, String desc, String signature, String[] exceptions) {
                methods.add(n + desc);
                Type methodType = Type.getMethodType(desc);
                collectType(methodType.getReturnType(), dependencies);
                for (Type t : methodType.getArgumentTypes()) collectType(t, dependencies);
                if (exceptions != null) for (String ex : exceptions) dependencies.add(ex.replace('/', '.'));
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int opcode, String type) { dependencies.add(type.replace('/', '.')); }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        dependencies.add(owner.replace('/', '.')); collectType(Type.getType(descriptor), dependencies);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        dependencies.add(owner.replace('/', '.')); Type mt = Type.getMethodType(descriptor);
                        collectType(mt.getReturnType(), dependencies); for (Type t : mt.getArgumentTypes()) collectType(t, dependencies);
                    }
                    @Override public void visitLdcInsn(Object value) { if (value instanceof String s) strings.add(s); else if (value instanceof Type t) collectType(t, dependencies); }
                    @Override public void visitInvokeDynamicInsn(String n, String desc, org.objectweb.asm.Handle bsm, Object... args) {
                        Type mt = Type.getMethodType(desc); collectType(mt.getReturnType(), dependencies); for (Type t : mt.getArgumentTypes()) collectType(t, dependencies);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        dependencies.remove(name[0].replace('/', '.'));
        String internal = name[0]; int slash = internal.lastIndexOf('/');
        String packageName = slash < 0 ? "(default package)" : internal.substring(0, slash).replace('/', '.');
        return new ClassInfo(name[0], packageName, superName[0] == null ? "—" : superName[0].replace('/', '.'), interfaces,
                access[0], major[0], fields[0], methods.size(), methods, strings.stream().distinct().toList(), dependencies.stream().sorted().toList());
    }
    private static void collectType(Type type, LinkedHashSet<String> dependencies) {
        if (type.getSort() == Type.ARRAY) collectType(type.getElementType(), dependencies);
        else if (type.getSort() == Type.OBJECT) dependencies.add(type.getClassName());
        else if (type.getSort() == Type.METHOD) { collectType(type.getReturnType(), dependencies); for (Type t : type.getArgumentTypes()) collectType(t, dependencies); }
    }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
