package org.example.stock.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MotDePasseValidator.class)
public @interface MotDePasseValide {
    String message() default "Le mot de passe doit contenir au moins 15 caractères et au maximum 72 octets en UTF-8";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
