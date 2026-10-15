package dev.herlysbyte.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class JarAnalyzerTest {
    @TempDir Path temp;
    @Test void rejectsNonJar() throws Exception {
        Path file=temp.resolve("not-jar.txt"); java.nio.file.Files.writeString(file,"hello");
        assertThrows(java.io.IOException.class,()->new JarAnalyzer().analyze(file));
    }
    @Test void readsEmptyArchive() throws Exception {
        Path file=temp.resolve("empty.jar"); try(OutputStream out=java.nio.file.Files.newOutputStream(file);JarOutputStream jar=new JarOutputStream(out)){jar.putNextEntry(new JarEntry("readme.txt"));jar.write("ok".getBytes());jar.closeEntry();}
        JarAnalysis result=new JarAnalyzer().analyze(file);
        assertEquals(0,result.classes().size()); assertEquals(1,result.resourceCount());
        assertEquals("readme.txt",result.resources().get(0).name()); assertEquals(2,result.resources().get(0).size());
    }
    @Test void decompilesClassFromJarToJavaSource() throws Exception {
        ClassWriter cw=new ClassWriter(0);
        cw.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_SUPER,"demo/Greeter",null,"java/lang/Object",null);
        MethodVisitor ctor=cw.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD,0); ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false); ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(1,1); ctor.visitEnd();
        MethodVisitor greet=cw.visitMethod(Opcodes.ACC_PUBLIC,"greet","()Ljava/lang/String;",null,null);
        greet.visitCode(); greet.visitLdcInsn("hello from bytecode"); greet.visitInsn(Opcodes.ARETURN); greet.visitMaxs(1,1); greet.visitEnd(); cw.visitEnd();
        Path file=temp.resolve("greeter.jar");
        try(OutputStream out=java.nio.file.Files.newOutputStream(file);JarOutputStream jar=new JarOutputStream(out)) { jar.putNextEntry(new JarEntry("demo/Greeter.class")); jar.write(cw.toByteArray()); jar.closeEntry(); }
        String java=new DecompilerService().decompile(file,"demo/Greeter");
        assertTrue(java.contains("class Greeter"),java);
        assertTrue(java.contains("hello from bytecode"),java);
        JarAnalysis analysis=new JarAnalyzer().analyze(file);
        String bytecode=new BytecodeDisassembler().disassemble(analysis,analysis.classes().get(0));
        assertTrue(bytecode.contains("INVOKESPECIAL"),bytecode);
        assertTrue(bytecode.contains("hello from bytecode"),bytecode);
    }
}
