package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Un fichier trop volumineux revient au formulaire avec un message compréhensible. */
@ControllerAdvice
public class LogoBoutiqueUploadAdvice {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tropVolumineux(MaxUploadSizeExceededException exception, HttpServletRequest request, RedirectAttributes redirection) {
        String chemin = request.getRequestURI().substring(request.getContextPath().length());
        if (!chemin.equals("/boutique/parametres") || !request.getMethod().equals("POST")) throw exception;
        redirection.addFlashAttribute("error", "Le logo accepte au maximum 2 Mo. Choisissez une image PNG ou JPEG plus légère.");
        return "redirect:/boutique/parametres";
    }
}
