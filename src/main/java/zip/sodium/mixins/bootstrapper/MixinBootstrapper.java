package zip.sodium.mixins.bootstrapper;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.sun.tools.attach.VirtualMachine;
import io.github.karlatemp.unsafeaccessor.Root;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import org.objectweb.asm.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.sodium.mixins.Mixins;
import zip.sodium.mixins.helper.UnsafeHelper;
import zip.sodium.mixins.mixin.annotations.Inject;
import zip.sodium.mixins.mixin.annotations.Mixin;
import zip.sodium.mixins.mixin.annotations.Shadow;
import zip.sodium.mixins.mixin.builtin.PlayerMixin;
import zip.sodium.mixins.mixin.info.InjectionPoint;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

public final class MixinBootstrapper implements PluginBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("MixinBootstrapper");

    private static final Multimap<String, String> pendingMixins = HashMultimap.create();

    private static Class<?>[] getTargets() {
        return pendingMixins.values()
                .stream()
                .map(target -> {
                    try {
                        return Class.forName(target);
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();

                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toArray(Class<?>[]::new);
    }

    private static byte[] transformClass(final String className, final byte[] data) {
        final var pending = pendingMixins.get(className);
        if (pending.isEmpty())
            return data;

        final var parentReader = new ClassReader(data);
        final var parentNode = new ClassNode();
        parentReader.accept(parentNode, 0);

        pending.forEach(mixin -> transformClassViaMixin(mixin, parentNode));
        pending.clear();

        final var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            public String getCommonSuperClass(String arg1, String arg2) {
                if ("java/lang/Object".equals(arg1) || "java/lang/Object".equals(arg2)) {
                    return "java/lang/Object";
                }

                return super.getCommonSuperClass(arg1, arg2);
            }
        };

        parentNode.accept(writer);

        SimpleVerifier verifier =
                new SimpleVerifier(
                        Type.getObjectType(parentNode.name),
                        parentNode.superName == null ? null : Type.getObjectType(parentNode.superName),
                        parentNode.interfaces.stream().map(Type::getObjectType).toList(),
                        false);
        Analyzer<BasicValue> analyzer = new Analyzer<>(verifier);
        try {
            analyzer.analyze(parentNode.name, parentNode.methods.stream().filter(x -> x.name.equals("l") && x.desc.equals("()V")).findFirst().get());
        } catch (AnalyzerException e) {
            final var t = new Textifier();
            e.node.accept(new TraceMethodVisitor(t));
            System.out.println(t.text);
        }

        return writer.toByteArray();
    }

    private static void transformClassViaMixin(final String mixin, final ClassNode parentNode) {
        final byte[] bytes = getBytecode(mixin);
        if (bytes == null) {
            LOGGER.warn("Ignoring mixin {} as the class isn't loaded", mixin);

            return;
        }

        final var reader = new ClassReader(bytes);

        final var injectType = Type.getType(Inject.class);
        final var shadowType = Type.getType(Shadow.class);

        final var mixinNode = new ClassNode();
        reader.accept(mixinNode, 0);

        final Map<String, String> shadedFields = findShadedFields(mixin, shadowType, mixinNode.fields);
        final Map<String, String> shadedMethods = findShadedMethods(mixin, shadowType, mixinNode.methods);

        mixinNode.methods.forEach(mixinMethod -> transformMethodViaMixin(mixin, mixinNode, parentNode, mixinMethod, shadedFields, shadedMethods, injectType));
    }

    private static void transformMethodViaMixin(final String mixin, final ClassNode mixinNode, final ClassNode parentNode, final MethodNode mixinMethod, final Map<String, String> shadedFields, final Map<String, String> shadedMethods, final Type injectType) {
        if (mixinMethod.name.equals("<init>") || mixinMethod.name.equals("<clinit>"))
            return;

        if (Modifier.isAbstract(mixinMethod.access)) {
            if (!shadedMethods.containsKey(mixinMethod.name + mixinMethod.desc))
                LOGGER.warn("Method \"{}\" in mixin class \"{}\" will be ignored as it is abstract and doesn't contain code!", mixinMethod.name, mixin);

            return;
        }

        if (mixinMethod.visibleAnnotations == null) {
            LOGGER.warn("Method \"{}\" in mixin class \"{}\" will be ignored as it isn't a mixin method! (Visible annotations are null)", mixinMethod.name, mixin);

            return;
        }

        final var optionalInjectAnnotation = mixinMethod.visibleAnnotations.stream()
                .filter(annotation -> injectType.equals(Type.getType(annotation.desc)))
                .findAny();

        if (optionalInjectAnnotation.isEmpty()) {
            LOGGER.warn("Method \"{}\" in mixin class \"{}\" will be ignored as it isn't a mixin method! (Couldn't find annotation)", mixinMethod.name, mixin);

            return;
        }

        String targetMethod = null;
        InjectionPoint injectionPoint = null;

        final var injectAnnotation = optionalInjectAnnotation.get();
        for (int i = 0; i < injectAnnotation.values.size(); i += 2) {
            final String name = (String) injectAnnotation.values.get(i);
            if (name.equals("method"))
                targetMethod = (String) injectAnnotation.values.get(i + 1);
            if (name.equals("at"))
                injectionPoint = InjectionPoint.valueOf(
                        ((String[]) injectAnnotation.values.get(i + 1))[1]
                );
        }

        final var finalTargetMethod = targetMethod;
        final var finalInjectionPoint = injectionPoint;

        final var t = new Textifier();
        mixinMethod.instructions.accept(new TraceMethodVisitor(t));
        System.out.println(t.text);

        parentNode.methods
                .forEach(parentMethod -> {
                    if (Modifier.isStatic(parentMethod.access) && !Modifier.isStatic(mixinMethod.access))
                        return;
                    if (!Modifier.isStatic(parentMethod.access) && Modifier.isStatic(mixinMethod.access))
                        return;

                    if (!(parentMethod.name + parentMethod.desc).equals(finalTargetMethod))
                        return;

                    parentMethod.instructions.forEach(insn -> {
                        if (insn instanceof VarInsnNode varInsnNode)
                            varInsnNode.var += mixinMethod.maxLocals;
                    });

                    mixinMethod.instructions.forEach(insn -> {
                        if (insn instanceof FieldInsnNode fieldInsnNode
                                && fieldInsnNode.owner.equals(mixinNode.name)) {
                            fieldInsnNode.name = shadedFields.get(fieldInsnNode.name);
                            fieldInsnNode.owner = parentNode.name;
                        }

                        if (insn instanceof MethodInsnNode methodInsnNode
                                && methodInsnNode.owner.equals(mixinNode.name)) {
                            methodInsnNode.name = shadedMethods.get(methodInsnNode.name + methodInsnNode.desc);
                            methodInsnNode.owner = parentNode.name;
                        }
                    });

                    LOGGER.warn("Injecting at {} of method \"{}#{}\"", finalInjectionPoint, parentNode.name, finalTargetMethod);

                    switch (finalInjectionPoint) {
                        case HEAD -> parentMethod.instructions.insert(mixinMethod.instructions);

                        case TAIL -> {
                            int parentReturnOpcode = Type.getReturnType(parentMethod.desc).getOpcode(Opcodes.IRETURN);

                            AbstractInsnNode parentLastReturn = null;
                            for (final var insn : parentMethod.instructions) {
                                if (insn instanceof InsnNode && insn.getOpcode() == parentReturnOpcode) {
                                    parentLastReturn = insn;
                                }
                            }

                            parentMethod.instructions.insertBefore(parentLastReturn, mixinMethod.instructions);
                        }
                    }
                });
    }

    private static Map<String, String> findShadedMethods(final String mixin, final Type shadowType, final List<MethodNode> methods) {
        return methods
                .stream()
                .map(method -> {
                    if (!Modifier.isAbstract(method.access))
                        return null;

                    if (method.visibleAnnotations == null) {
                        LOGGER.warn("Abstract method \"{}\" in mixin class \"{}\" will be ignored as it isn't a shaded method! (Visible annotations are null)", method.name, mixin);

                        return null;
                    }

                    final var optionalShadowAnnotation = method.visibleAnnotations.stream()
                            .filter(annotation -> shadowType.equals(Type.getType(annotation.desc)))
                            .findAny();

                    if (optionalShadowAnnotation.isEmpty()) {
                        LOGGER.warn("Abstract method \"{}\" in mixin class \"{}\" will be ignored as it isn't a shaded method! (Couldn't find annotation)", method.name, mixin);

                        return null;
                    }

                    String obfuscatedName = null;

                    final var shadowAnnotation = optionalShadowAnnotation.get();
                    for (int i = 0; i < shadowAnnotation.values.size(); i += 2) {
                        final String name = (String) shadowAnnotation.values.get(i);
                        if (name.equals("obfuscatedName"))
                            obfuscatedName = (String) shadowAnnotation.values.get(i + 1);
                    }

                    if (obfuscatedName == null)
                        obfuscatedName = method.name;

                    return Pair.of(method.name + method.desc, obfuscatedName);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        Pair::getKey,
                        Pair::getValue
                ));
    }

    private static Map<String, String> findShadedFields(final String mixin, final Type shadowType, final List<FieldNode> fields) {
        return fields
                .stream()
                .map(field -> {
                    if (field.visibleAnnotations == null) {
                        LOGGER.warn("Field \"{}\" in mixin class \"{}\" will be ignored as it isn't a shaded field! (Visible annotations are null)", field.name, mixin);

                        return null;
                    }

                    final var optionalShadowAnnotation = field.visibleAnnotations.stream()
                            .filter(annotation -> shadowType.equals(Type.getType(annotation.desc)))
                            .findAny();

                    if (optionalShadowAnnotation.isEmpty()) {
                        LOGGER.warn("Field \"{}\" in mixin class \"{}\" will be ignored as it isn't a shaded field! (Couldn't find annotation)", field.name, mixin);

                        return null;
                    }

                    String obfuscatedName = null;

                    final var shadowAnnotation = optionalShadowAnnotation.get();
                    for (int i = 0; i < shadowAnnotation.values.size(); i += 2) {
                        final String name = (String) shadowAnnotation.values.get(i);
                        if (name.equals("obfuscatedName"))
                            obfuscatedName = (String) shadowAnnotation.values.get(i + 1);
                    }

                    if (obfuscatedName == null)
                        obfuscatedName = field.name;

                    return Pair.of(field.name, obfuscatedName);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        Pair::getKey,
                        Pair::getValue
                ));
    }

    private static final Map<String, byte[]> bytecodes = new HashMap<>();

    private static void setClassBytecode(final String name, final byte[] data) {
        bytecodes.put(name, data);
    }

    public static byte[] getBytecode(String name) {
        name = name.replace('.', '/');

        final var b = bytecodes.get(name);
        if (b == null) {
            try {
                return new ClassReader(name).b;
            } catch (IOException ignored) { }
        }

        return b;
    }

    public static void addMixin(final Class<?> mixinClass) {
        final var annotation = mixinClass.getAnnotation(Mixin.class);
        if (annotation == null) {
            LOGGER.warn("Mixin \"{}\" ignored as it doesn't have the mixin annotation present", mixinClass.getCanonicalName());

            return;
        }

        pendingMixins.put(
                annotation.target().getCanonicalName(),
                mixinClass.getCanonicalName()
        );
    }

    private BootstrapContext context;

    @Override
    public void bootstrap(final @NotNull BootstrapContext context) {
        this.context = context;

        addMixin(PlayerMixin.class); // todo automate this soon

        try {
            bootstrap();
        } catch (final Exception e) {
            LOGGER.error("Mixins will not be applied", e);
        }
    }

    private void bootstrap() throws Exception {
        final String nameOfRunningVM = ManagementFactory.getRuntimeMXBean().getName();
        final String pid = nameOfRunningVM.substring(0, nameOfRunningVM.indexOf('@'));

        Root.getModuleAccess().addOpensToAllUnnamed(
                VirtualMachine.class.getModule(),
                "sun.tools.attach"
        );

        UnsafeHelper.setStaticField(
                Class.forName("sun.tools.attach.HotSpotVirtualMachine").getDeclaredField("ALLOW_ATTACH_SELF"),
                true
        );

        final var vm = VirtualMachine.attach(pid);
        vm.loadAgent(context.getPluginSource().toAbsolutePath().toString(), "");
        vm.detach();
    }

    @Override
    public @NotNull JavaPlugin createPlugin(final @NotNull PluginProviderContext context) {
        return new Mixins();
    }
}
