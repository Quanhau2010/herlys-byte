package dev.herlysbyte.analysis;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Decompiles selected archive classes to readable Java source; never loads or executes them. */
public final class DecompilerService {
    public String decompile(Path archive, String internalClassName) throws IOException {
        Path temp = Files.createTempDirectory("herlysbyte-cfr-");
        String entryName = internalClassName + ".class";
        String nestedPrefix = internalClassName + "$";
        StringBuilder output = new StringBuilder();
        try (JarFile jar = new JarFile(archive.toFile(), false)) {
            JarEntry main = jar.getJarEntry(entryName);
            if (main == null) throw new IOException("Class không còn trong JAR: " + internalClassName.replace('/', '.'));
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".class") || !(name.equals(entryName) || name.startsWith(nestedPrefix) && name.endsWith(".class"))) continue;
                Path target = temp.resolve(name).normalize();
                if (!target.startsWith(temp)) throw new IOException("Đường dẫn class không hợp lệ trong JAR.");
                Files.createDirectories(target.getParent());
                try (var in = jar.getInputStream(e)) { Files.copy(in, target); }
            }
            OutputSinkFactory sink = new OutputSinkFactory() {
                @Override public List<SinkClass> getSupportedSinks(SinkType type, Collection<SinkClass> available) {
                    return type == SinkType.JAVA ? List.of(SinkClass.DECOMPILED) : List.of(SinkClass.STRING);
                }
                @Override @SuppressWarnings("unchecked") public <T> Sink<T> getSink(SinkType type, SinkClass sinkClass) {
                    if (type == SinkType.JAVA) return value -> output.append(((SinkReturns.Decompiled) value).getJava());
                    return value -> { };
                }
            };
            CfrDriver driver = new CfrDriver.Builder().withOutputSink(sink)
                    .withOptions(Map.of("silent", "true", "comments", "false", "showversion", "false"))
                    .build();
            driver.analyse(List.of(temp.resolve(entryName).toString()));
            if (output.isEmpty()) throw new IOException("Decompiler không trả về mã nguồn cho class này.");
            return output.toString();
        } catch (RuntimeException e) {
            throw new IOException("Không thể decompile class: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        } finally {
            try (var paths = Files.walk(temp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            }
        }
    }
}
