package zip.sodium.mixins.mixin.annotations;

import zip.sodium.mixins.mixin.info.InjectionPoint;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {
    String method();
    InjectionPoint at();
}
