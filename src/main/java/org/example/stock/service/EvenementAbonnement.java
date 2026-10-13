package org.example.stock.service;

import org.example.stock.enums.ActionAbonnement;

import java.time.LocalDateTime;

/** L'historique public de la boutique ne révèle pas l'email ou le jeton de son administrateur. */
public record EvenementAbonnement(Long id, ActionAbonnement action, String acteurNom,
                                  LocalDateTime creeLe, LocalDateTime periodeDebut,
                                  LocalDateTime periodeFin, LocalDateTime ancienneFin,
                                  String motif, Integer quantite) {}
