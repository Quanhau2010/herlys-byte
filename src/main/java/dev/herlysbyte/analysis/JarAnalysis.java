package dev.herlysbyte.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;

public record JarAnalysis(Path file, long fileSize, Map<String, String> manifest,
                          List<ClassInfo> classes, List<JarResourceInfo> resources, List<String> warnings) {
    public JarAnalysis {
        manifest = Map.copyOf(manifest);
        classes = List.copyOf(classes);
        resources = List.copyOf(resources);
        warnings = List.copyOf(warnings);
    }
    public int resourceCount() { return resources.size(); }
    public String mainClass() { return manifest.getOrDefault(Attributes.Name.MAIN_CLASS.toString(), "—"); }
    public int methodCount() { return classes.stream().mapToInt(ClassInfo::methodCount).sum(); }
    public int fieldCount() { return classes.stream().mapToInt(ClassInfo::fieldCount).sum(); }
}
