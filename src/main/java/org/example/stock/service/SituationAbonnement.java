package org.example.stock.service;

import org.example.stock.enums.StatutAbonnement;

import java.time.LocalDateTime;

/** Vue sans utilisateur, mot de passe ni jeton ; utilisable par les pages de la boutique. */
public record SituationAbonnement(Long boutiqueId, String nomBoutique, StatutAbonnement statut,
                                 LocalDateTime essaiDebut, LocalDateTime essaiFin,
                                 LocalDateTime abonnementDebut, LocalDateTime abonnementFin,
                                 long joursRestants, boolean actif, boolean accesOffert,
                                 boolean accesAutorise, boolean essaiProlongeable) {}
