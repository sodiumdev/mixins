package zip.sodium.mixins.helper;

import io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess;

import java.lang.reflect.Field;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

public final class UnsafeHelper {
    private UnsafeHelper() {}

    private static final Map<String, Field> fieldCache = new HashMap<>();

    public static void defineClass(final ClassLoader loader, final String name, final byte[] data) {
        final var unsafe = io.github.karlatemp.unsafeaccessor.UnsafeAccess.getInstance().getUnsafe();

        unsafe.defineClass(
                name,
                data,
                0,
                data.length,
                loader,
                new ProtectionDomain(
                        new CodeSource(
                                null,
                                (CodeSigner[]) null
                        ),
                        null
                )
        );
    }

    public static void setStaticField(final String ownerName, final String fieldName, final Object value) {
        setStaticField(
                fieldCache.computeIfAbsent(
                        ownerName + "#" + fieldName,
                        ignored -> {
                            try {
                                return Class.forName(ownerName).getField(fieldName);
                            } catch (ClassNotFoundException | NoSuchFieldException e) {
                                throw new RuntimeException(e);
                            }
                        }
                ),
                value
        );
    }

    public static void setStaticField(final Field field, final Object value) {
        final var unsafe = UnsafeAccess.UNSAFE;

        field.setAccessible(true);

        final var staticFieldBase = unsafe.staticFieldBase(field);
        final long staticFieldOffset = unsafe.staticFieldOffset(field);

        if (value instanceof Integer val) {
            unsafe.putInt(staticFieldBase, staticFieldOffset, val);
        } else if (value instanceof Short val) {
            unsafe.putShort(staticFieldBase, staticFieldOffset, val);
        } else if (value instanceof Boolean val) {
            unsafe.putBoolean(staticFieldBase, staticFieldOffset, val);
        } else if (value instanceof Float val) {
            unsafe.putFloat(staticFieldBase, staticFieldOffset, val);
        } else if (value instanceof Double val) {
            unsafe.putDouble(staticFieldBase, staticFieldOffset, val);
        } else if (value instanceof Byte val) {
            unsafe.putByte(staticFieldBase, staticFieldOffset, val);
        } else unsafe.putObject(staticFieldBase, staticFieldOffset, value);
    }

    public static void setField(final Object base, final String fieldName, final Object value) {
        final var baseClass = base.getClass();

        setField(
                baseClass,
                fieldCache.computeIfAbsent(
                        baseClass.getName() + "#" + fieldName,
                        ignored -> {
                            try {
                                return baseClass.getField(fieldName);
                            } catch (NoSuchFieldException e) {
                                throw new RuntimeException(e);
                            }
                        }
                ),
                value
        );
    }

    public static void setField(final Object base, final Field field, final Object value) {
        final var unsafe = UnsafeAccess.UNSAFE;

        final var offset = unsafe.objectFieldOffset(field);

        if (value instanceof Integer val) {
            unsafe.putInt(base, offset, val);
        } else if (value instanceof Short val) {
            unsafe.putShort(base, offset, val);
        } else if (value instanceof Boolean val) {
            unsafe.putBoolean(base, offset, val);
        } else if (value instanceof Float val) {
            unsafe.putFloat(base, offset, val);
        } else if (value instanceof Double val) {
            unsafe.putDouble(base, offset, val);
        } else if (value instanceof Byte val) {
            unsafe.putByte(base, offset, val);
        } else unsafe.putObject(base, offset, value);
    }

    public static Object getField(final Object base, final Field field) {
        final var unsafe = UnsafeAccess.UNSAFE;

        return unsafe.getObject(
                base,
                unsafe.objectFieldOffset(field)
        );
    }

    public static Object getStaticField(final Field field) {
        final var unsafe = UnsafeAccess.UNSAFE;

        return unsafe.getObject(
                unsafe.staticFieldBase(field),
                unsafe.staticFieldOffset(field)
        );
    }
}
