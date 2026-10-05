package org.example.stock.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class MotDePasseValidator implements ConstraintValidator<MotDePasseValide, String> {
    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        String erreur = null;
        if (password == null || password.isBlank()) erreur = "Le mot de passe est obligatoire";
        else if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            erreur = "Le mot de passe est trop long (maximum 72 octets en UTF-8).";
        else if (password.codePointCount(0, password.length()) < 15)
            erreur = "Le mot de passe doit contenir au moins 15 caractères";
        if (erreur == null) return true;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(erreur).addConstraintViolation();
        return false;
    }
}
