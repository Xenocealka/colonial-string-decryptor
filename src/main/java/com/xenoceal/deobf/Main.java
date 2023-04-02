package com.xenoceal.deobf;

import lombok.val;
import lombok.var;
import org.apache.commons.cli.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/* Sorry for shit code :c */
public final class Main
        implements Opcodes {

    private final Map<String, ClassNode> classes;

    private char[] charArray;

    private Main() {
        this.classes = new HashMap<>();
    }

    private void start(String[] args)
            throws IOException {
        val options = new Options();

        val input = new Option("in", "input", true, "input file path");
        input.setRequired(true);

        val output = new Option("out", "output", true, "output file path");
        output.setRequired(true);

        options.addOption(input);
        options.addOption(output);

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

        for (val node : classes.values()) {
            if ((node.access & ACC_SYNTHETIC) != 0)
                node.access &= ~ACC_SYNTHETIC;

            for (val method : node.methods) {
                if (!method.name.equals("<clinit>"))
                    continue;

                val insnList = new ArrayList<AbstractInsnNode>();

                for (val insn : method.instructions.toArray()) {
                    if (insn.getOpcode() == BIPUSH)
                        insnList.add(insn);
                }

                insnList.remove(0);

                val chars = new char[insnList.size()];

                for (var i = 0; i < insnList.size(); ++i) {
                    val intInsn = (IntInsnNode) insnList.get(i);
                    chars[i] = (char) intInsn.operand;
                }

                charArray = decrypt((String) ((LdcInsnNode) method.instructions.get(0)).cst, chars);

                insnList.clear();

                var deleted = false;
                for (val insn : method.instructions.toArray()) {
                    if (insn instanceof FieldInsnNode) {
                        if (deleted)
                            continue;

                        if (insn.getOpcode() != PUTSTATIC)
                            continue;

                        val fInsn = (FieldInsnNode) insn;

                        if (fInsn.desc.equals("[C"))
                            node.fields.removeIf(fieldNode ->
                                    fieldNode.name.equals(fInsn.name) &&
                                    fieldNode.desc.equals(fInsn.desc)
                            );

                        val previous = insn.getPrevious();
                        if (previous instanceof MethodInsnNode) {
                            if (previous.getOpcode() != INVOKEVIRTUAL)
                                continue;

                            val mInsn = (MethodInsnNode) previous;
                            if (mInsn.name.equals("toCharArray") && mInsn.desc.equals("()[C")) {
                                method.instructions.remove(previous);
                                method.instructions.remove(fInsn);
                            }
                        }

                        deleted = true;
                    } else if (insn instanceof MethodInsnNode) {
                        val mInsn = (MethodInsnNode) insn;

                        if (mInsn.getOpcode() == INVOKEVIRTUAL) {
                            if (mInsn.name.equals("intern") && mInsn.desc.equals("()Ljava/lang/String;")) {
                                var next = mInsn.getNext()
                                        .getNext()
                                        .getNext()
                                        .getNext()
                                        .getNext();

                                val index = method.instructions.indexOf(next);

                                for (var i = index + 1; i < method.instructions.size(); ++i)
                                    insnList.add(method.instructions.get(i));

                                method.instructions.clear();

                                for (val insnNode : insnList)
                                    method.instructions.add(insnNode);

                                break;
                            }
                        }
                    }
                }
            }

            for (val method : node.methods) {
                if ((method.access & ACC_SYNTHETIC) != 0)
                    method.access &= ~ACC_SYNTHETIC;

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

            for (val field : node.fields)
                if ((field.access & ACC_SYNTHETIC) != 0)
                    field.access &= ~ACC_SYNTHETIC;

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

    private char[] decrypt(String str, char[] chars) {
        val charArray = str.toCharArray();
        val i = charArray.length;
        for (var n = 0; i > n; ++n) {
            val c = charArray[n];
            var c2 = Character.MIN_VALUE;
            switch (n % 7) {
                case 0:
                    c2 = chars[0];
                    break;
                case 1:
                    c2 = chars[1];
                    break;
                case 2:
                    c2 = chars[2];
                    break;
                case 3:
                    c2 = chars[3];
                    break;
                case 4:
                    c2 = chars[4];
                    break;
                case 5:
                    c2 = chars[5];
                    break;
                default:
                    c2 = chars[6];
                    break;
            }
            charArray[n] = (char) (c ^ c2);
        }
        return new String(charArray).intern().toCharArray();
    }

    private int xor(int n, int n2) {
        return ((n | n2) << 1) + ~(n ^ n2) + 1;
    }

    public static void main(String[] args)
            throws IOException {
        new Main().start(args);
    }

}
