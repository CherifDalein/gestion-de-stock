package org.example.stock.service;

import org.example.stock.enums.Role;

import java.time.LocalDate;

/** Informations de consultation, sans mot de passe ni identifiant permettant de choisir une cible. */
public record ComptePersonnel(String nom, String email, Role role, LocalDate dateInscription, String nomBoutique) {
}
