package zip.sodium.mixins.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Arrays;

public final class MixinAgent {
    private static Method transformClassMethod;
    private static Method setClassBytecodeMethod;

    public static void agentmain(String agentArgs, Instrumentation inst) {
        try {
            final var loadedClasses = Arrays.stream(inst.getAllLoadedClasses()).filter(inst::isModifiableClass).toArray(Class[]::new);
            final var bootstrapperClass = Arrays.stream(inst.getAllLoadedClasses())
                    .filter(
                            x -> x.getName().equals("zip.sodium.mixins.bootstrapper.MixinBootstrapper")
                    )
                    .findFirst()
                    .get();

            setClassBytecodeMethod = bootstrapperClass.getDeclaredMethod("setClassBytecode", String.class, byte[].class);
            transformClassMethod = bootstrapperClass.getDeclaredMethod("transformClass", String.class, byte[].class);

            setClassBytecodeMethod.setAccessible(true);
            transformClassMethod.setAccessible(true);

            inst.addTransformer(new BytecodeGrabber(), true);
            inst.retransformClasses(loadedClasses);

            inst.addTransformer(new MixinClassTransformer(), true);
            inst.retransformClasses(loadedClasses);

            final var getTargetsMethod = bootstrapperClass.getDeclaredMethod("getTargets");
            getTargetsMethod.setAccessible(true);

            inst.retransformClasses(
                    (Class<?>[]) getTargetsMethod.invoke(null)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class BytecodeGrabber implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            try {
                setClassBytecodeMethod.invoke(null, className, classfileBuffer);
            } catch (IllegalAccessException | InvocationTargetException ignored) {}

            return classfileBuffer;
        }
    }

    private static class MixinClassTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className.startsWith("java."))
                return classfileBuffer;

            byte[] bytes;
            try {
                bytes = (byte[]) transformClassMethod.invoke(
                        null,
                        className.replace("/", "."),
                        classfileBuffer
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            return bytes;
        }
    }
}
