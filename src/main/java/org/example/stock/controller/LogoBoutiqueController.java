package org.example.stock.controller;

import org.example.stock.service.LogoBoutiqueService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LogoBoutiqueController {
    private final LogoBoutiqueService logos;

    public LogoBoutiqueController(LogoBoutiqueService logos) { this.logos = logos; }

    @GetMapping("/boutique/logo")
    public ResponseEntity<byte[]> afficher() {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(logos.getPourBoutiqueCourante());
    }
}
