package org.example.stock.form;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Actions du pointage : une paire ligne/mouvement, une sélection de plusieurs, un rapprochement à défaire, une nature à enregistrer. */
@Getter
@Setter
public class PointageForm {
    private Long ligne;
    private Long mouvement;
    private Long rapprochement;
    private List<Long> lignes = new ArrayList<>();
    private List<Long> mouvements = new ArrayList<>();
    private String nature;
}
