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
import java.util.stream.Stream;

/* Sorry for shit code :c */
public final class Main
        implements Opcodes {

    private static final Map<String, ClassNode> CLASS_NODE_MAP;

    public static void main(String[] args)
            throws IOException {
        val options = new Options();

        val input = new Option("in", "input", true, "input file path");
        input.setRequired(true);

        val output = new Option("out", "output", true, "output file path");
        output.setRequired(true);

        val libs = new Option("libs", "libraries", true, "path to library directory");
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

        val libsPath = cmd.getOptionValue("libraries");

        if (libsPath != null) {
            val file = new File(libsPath);
            try (Stream<Path> stream = Files.walk(file.toPath())) {
                stream.forEach(path -> {
                    try {
                        addURL(path.toFile().toURI().toURL());
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                });
            }
        }

        val inputPath = cmd.getOptionValue("input");
        val outputPath = cmd.getOptionValue("output");

        addURL(new File(inputPath).toURI().toURL());

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

            CLASS_NODE_MAP.put(node.name, node);

            inputStream.close();
        }

        for (val node : CLASS_NODE_MAP.values()) {
            for (val method : node.methods) {
                for (val instruction : method.instructions) {
                    if (instruction.getOpcode() == INVOKEVIRTUAL || instruction.getOpcode() == INVOKESTATIC) {
                        if (instruction instanceof MethodInsnNode) {
                            val methodInsnNode = (MethodInsnNode) instruction;
                            if (methodInsnNode.desc.equals("(IILjava/lang/String;II)Ljava/lang/String;")) {
                                try {
                                    val clazz = Class.forName(methodInsnNode.owner.replace('/', '.'), true, ClassLoader.getSystemClassLoader());
                                    val mtd = clazz.getDeclaredMethod(methodInsnNode.name, int.class, int.class, String.class, int.class, int.class);

                                    val abstractInsnNodes = new AbstractInsnNode[6];
                                    abstractInsnNodes[0] = methodInsnNode;

                                    for (var i = 1; i < abstractInsnNodes.length; ++i)
                                        abstractInsnNodes[i] = abstractInsnNodes[i - 1].getPrevious();

                                    val ldc1 = (LdcInsnNode) abstractInsnNodes[5];
                                    val ldc2 = (LdcInsnNode) abstractInsnNodes[4];
                                    val ldc3 = (LdcInsnNode) abstractInsnNodes[3];
                                    val ldc4 = (LdcInsnNode) abstractInsnNodes[2];
                                    val ldc5 = (LdcInsnNode) abstractInsnNodes[1];

                                    val decryptString = (String) mtd.invoke(
                                            null,
                                            (int) ldc1.cst,
                                            (int) ldc2.cst,
                                            (String) ldc3.cst,
                                            (int) ldc4.cst,
                                            (int) ldc5.cst
                                    );

                                    method.instructions.insert(abstractInsnNodes[abstractInsnNodes.length -1 ], new LdcInsnNode(decryptString));

                                    for (val abstractInsnNode : abstractInsnNodes)
                                        method.instructions.remove(abstractInsnNode);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }
            }

            node.methods.removeIf(methodNode -> methodNode.desc.equals("(IILjava/lang/String;II)Ljava/lang/String;"));
        }

        val jos = new JarOutputStream(Files.newOutputStream(Paths.get(outputPath)));

        for (Map.Entry<String, ClassNode> entry : CLASS_NODE_MAP.entrySet()) {
            val node = entry.getValue();
            val writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            node.accept(writer);

            jos.putNextEntry(new JarEntry(entry.getKey().concat(".class")));
            jos.write(writer.toByteArray());
        }

        jarFile.close();
        jos.close();
    }

    private static void addURL(URL url) {
        try {
            val addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
            addURL.invoke(ClassLoader.getSystemClassLoader(), url);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static {
        CLASS_NODE_MAP = new HashMap<>();
    }

}
