package dev.herlysbyte.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.jar.JarFile;

/** Converts a class file to ASM's textual bytecode trace without defining or executing it. */
public final class BytecodeDisassembler {
    public String disassemble(JarAnalysis jar, ClassInfo info) throws IOException {
        String entryName = info.name() + ".class";
        try (JarFile file = new JarFile(jar.file().toFile(), false)) {
            var entry = file.getJarEntry(entryName);
            if (entry == null) throw new IOException("Không tìm thấy class trong JAR: " + info.displayName());
            try (var in = file.getInputStream(entry); StringWriter out = new StringWriter(); PrintWriter writer = new PrintWriter(out)) {
                new ClassReader(in).accept(new TraceClassVisitor(writer), ClassReader.SKIP_DEBUG);
                writer.flush();
                return out.toString();
            }
        }
    }
}
