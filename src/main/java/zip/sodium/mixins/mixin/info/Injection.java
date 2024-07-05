package zip.sodium.mixins.mixin.info;

import org.objectweb.asm.tree.InsnList;

public record Injection(InsnList sourceMethod, String targetMethod, InjectionPoint injectionPoint) { }
