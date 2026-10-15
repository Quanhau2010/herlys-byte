package dev.herlysbyte.analysis;

import java.util.List;

public record ClassInfo(String name, String packageName, String superName, List<String> interfaces,
                        int access, int majorVersion, int fieldCount, int methodCount,
                        List<String> methods, List<String> strings, List<String> dependencies) {
    public ClassInfo {
        interfaces = List.copyOf(interfaces);
        methods = List.copyOf(methods);
        strings = List.copyOf(strings);
        dependencies = List.copyOf(dependencies);
    }
    public String displayName() { return name.replace('/', '.'); }
}
