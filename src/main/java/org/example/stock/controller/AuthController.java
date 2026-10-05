package org.example.stock.controller;

import org.example.stock.service.UtilisateurService;
import org.example.stock.service.EmailDejaUtiliseException;
import org.example.stock.form.InscriptionForm;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class AuthController {

    @Autowired
    private UtilisateurService utilisateurService;

    @GetMapping("/register")
    public String showRegisterForm(Model model) {
        model.addAttribute("inscription", new InscriptionForm());
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(
            @Valid @ModelAttribute("inscription") InscriptionForm formulaire,
            BindingResult result
    ) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                utilisateurService.registerUtilisateur(formulaire.getNom(), formulaire.getEmail(), formulaire.getPassword());
                return "redirect:/register?created";
            } catch (EmailDejaUtiliseException e) {
                result.rejectValue("email", "email.duplique", e.getMessage());
            } catch (IllegalArgumentException e) {
                result.reject("inscription.invalide", e.getMessage());
            } catch (DataAccessException e) {
                result.reject("inscription.indisponible", "Le compte n'a pas pu être créé. Réessayez plus tard.");
            }
        }
        formulaire.setPassword(null);
        return "register";
    }

    @GetMapping("/login")
    public String showLoginForm() {
        return "login";
    }
}
