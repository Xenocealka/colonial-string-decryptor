package com.xenoceal.deobf;

import lombok.val;
import lombok.var;
import org.apache.commons.cli.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/* Sorry for shit code :c */
public final class Main
        extends URLClassLoader implements Opcodes {

    private final Map<String, ClassNode> classes;

    private char[] charArray;

    private Main() {
        super(new URL[0]);
        this.classes = new HashMap<>();
    }

    private void start(String[] args)
            throws IOException {
        val options = new Options();

        val input = new Option("in", "input", true, "input file path");
        input.setRequired(true);

        val output = new Option("out", "output", true, "output file path");
        output.setRequired(true);

        val libs = new Option("libs", "libraries", true, "path to libraries");
        libs.setRequired(false);

        options.addOption(input);
        options.addOption(output);
        options.addOption(libs);

        val parser = new DefaultParser();
        val formatter = new HelpFormatter();

        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(e.getLocalizedMessage());
            formatter.printHelp("java -jar <name>", options);
            return;
        }

        val inputPath = cmd.getOptionValue("input");
        val outputPath = cmd.getOptionValue("output");

        val jarFile = new JarFile(inputPath);
        val entries = jarFile.entries();

        while (entries.hasMoreElements()) {
            val entry = (JarEntry) entries.nextElement();

            if (entry.isDirectory() || !entry.getName().endsWith(".class"))
                continue;

            val inputStream = jarFile.getInputStream(entry);

            val node = new ClassNode(ASM8);
            val reader = new ClassReader(inputStream);

            reader.accept(node, ClassReader.SKIP_DEBUG);

            classes.put(node.name, node);

            inputStream.close();
        }

        val libsPath = cmd.getOptionValue("libraries");
        if (libsPath != null) {
            try (val stream = Files.walk(Paths.get(libsPath))) {
                stream.map(Path::toFile).forEach(file -> {
                    try {
                        addURL(file.toURI().toURL());
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                });
            }
        }

        addURL(new File(inputPath).toURI().toURL());

        for (val node : classes.values()) {
            for (val method : node.methods) {
                if (!method.name.equals("<clinit>"))
                    continue;

                for (val insn : method.instructions) {
                    if (insn instanceof FieldInsnNode) {
                        val fInsn = (FieldInsnNode) insn;

                        if (!fInsn.desc.equals("[C"))
                            continue;

                        try {
                            val clazz = Class.forName(node.name.replace('/', '.'), true, this);
                            val field = getField(clazz, fInsn.name, char[].class);

                            if (field == null)
                                continue;

                            charArray = (char[]) field.get(null);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        break;
                    }
                }
            }

            for (val method : node.methods) {
                for (val insn : method.instructions) {
                    if (insn.getOpcode() == INVOKEVIRTUAL || insn.getOpcode() == INVOKESTATIC) {
                        if (insn instanceof MethodInsnNode) {
                            val mInsn = (MethodInsnNode) insn;

                            if (mInsn.desc.equals("(IILjava/lang/String;II)Ljava/lang/String;")) {
                                val abstractInsnNodes = new AbstractInsnNode[6];
                                abstractInsnNodes[0] = mInsn;

                                for (var i = 1; i < abstractInsnNodes.length; ++i)
                                    abstractInsnNodes[i] = abstractInsnNodes[i - 1].getPrevious();

                                val ldc1 = (LdcInsnNode) abstractInsnNodes[5];
                                val ldc2 = (LdcInsnNode) abstractInsnNodes[4];
                                val ldc3 = (LdcInsnNode) abstractInsnNodes[3];
                                val ldc4 = (LdcInsnNode) abstractInsnNodes[2];
                                val ldc5 = (LdcInsnNode) abstractInsnNodes[1];

                                val decryptString = decrypt(
                                        (int) ldc1.cst,
                                        (int) ldc2.cst,
                                        (String) ldc3.cst,
                                        (int) ldc4.cst,
                                        (int) ldc5.cst
                                );

                                method.instructions.insert(abstractInsnNodes[abstractInsnNodes.length - 1], new LdcInsnNode(decryptString));

                                for (val abstractInsnNode : abstractInsnNodes)
                                    method.instructions.remove(abstractInsnNode);
                            }
                        }
                    }
                }
            }

            node.methods.removeIf(methodNode -> methodNode.desc.equals("(IILjava/lang/String;II)Ljava/lang/String;"));
        }

        val jos = new JarOutputStream(Files.newOutputStream(Paths.get(outputPath)));

        for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
            val node = entry.getValue();
            val writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            node.accept(writer);

            jos.putNextEntry(new JarEntry(entry.getKey().concat(".class")));
            jos.write(writer.toByteArray());
        }

        jarFile.close();
        jos.close();
    }

    public static void main(String[] args)
            throws IOException {
        try (val main = new Main()) {
            main.start(args);
        }
    }

    private String decrypt(int n, int n2, String str, int n3, int n4) {
        val builder = new StringBuilder();
        var n5 = 0;
        val chars = str.toCharArray();
        var n6 = chars.length;
        var n7 = 0;
        while (n7 < n6) {
            val c = chars[n7];
            builder.append((char) (c ^ charArray[n5 % charArray.length] ^ (n ^ xor(n3, n5)) ^ n2 ^ n4));
            ++n5;
            ++n7;
        }
        return builder.toString();
    }

    private int xor(int n, int n2) {
        return ((n | n2) << 1) + ~(n ^ n2) + 1;
    }

    private Field getField(Class<?> clazz, String name, Class<?> type) {
        Field field = null;

        for (val f : clazz.getDeclaredFields()) {
            if (f.getName().equals(name) && f.getType() == type) {
                field = f;
                break;
            }
        }

        if (field != null)
            field.setAccessible(true);

        return field;
    }

}
