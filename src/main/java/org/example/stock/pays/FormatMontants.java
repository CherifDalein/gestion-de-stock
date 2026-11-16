package org.example.stock.pays;

import org.example.stock.export.MontantEnLettres;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * UN SEUL point de formatage des montants côté serveur : bean {@code montants} des gabarits Thymeleaf
 * ({@code ${@montants.format(x)}}) et des services (messages, PDF, exports). Il connaît la monnaie de la boutique du compte connecté.
 *
 * <p>Règles (voir {@code docs/pays-monnaie.md}) : jamais de décimales à l'affichage (arrondi {@code HALF_UP} du montant exact ; la base
 * garde {@code DECIMAL(17,2)}), espace insécable entre les milliers et avant le symbole. La somme affichée d'une colonne est
 * l'arrondi de la somme réelle, pas la somme des valeurs arrondies : elle peut donc différer d'un franc de l'addition à la main des lignes.</p>
 *
 * <p>La monnaie est lue une fois par requête. Sans compte connecté (page publique, traitement hors requête), c'est celle du pays par
 * défaut ({@link Pays#PAR_DEFAUT}, le franc guinéen) ; les services qui connaissent la boutique passent sa monnaie explicitement
 * ({@link Monnaie#formater(Number)}).</p>
 */
@Component("montants")
public class FormatMontants {
    private final PaysCourant paysCourant;

    public FormatMontants(PaysCourant paysCourant) { this.paysCourant = paysCourant; }

    /** Monnaie de la boutique du compte connecté. */
    public Monnaie monnaie() { return paysCourant.monnaie(); }

    /** « 150 000 FCFA » ; vide si le montant est absent. */
    public String format(Number valeur) { return monnaie().formater(valeur); }

    /** « 150 000 » : sans symbole (colonnes dont l'en-tête porte déjà la monnaie). */
    public String nombre(Number valeur) { return Monnaie.nombre(valeur); }

    /** « + 5 000 FCFA » / « − 5 000 FCFA » / « 0 FCFA ». */
    public String signe(Number valeur) { return monnaie().formaterAvecSigne(valeur); }

    /** « GNF » ou « FCFA ». */
    public String symbole() { return monnaie().getSymbole(); }

    /** Code ISO de la monnaie : « GNF », « XOF » ou « XAF ». */
    public String code() { return monnaie().getCode(); }

    /** « cent quarante mille francs CFA » (voir {@link MontantEnLettres}). */
    public String lettres(BigDecimal valeur) { return MontantEnLettres.montant(valeur, monnaie()); }
}
