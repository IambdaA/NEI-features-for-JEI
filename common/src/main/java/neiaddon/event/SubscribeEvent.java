package neiaddon.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a static one-argument method as a handler for that argument's event class (see {@link Bus#register}). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {

    EventPriority priority() default EventPriority.NORMAL;
}
