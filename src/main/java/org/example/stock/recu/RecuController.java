package org.example.stock.recu;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Reçus PDF (lecture seule), ouverts dans un nouvel onglet : {@code Content-Disposition: inline}, prêts à imprimer ou à partager.
 *
 * <p>Chaque adresse est placée sous le préfixe de la page qu'elle prolonge et hérite de sa règle de sécurité
 * ({@code SecurityConfig}) : {@code /ventes/**} et {@code /clients/**} pour le gérant et le caissier, {@code /achats/**} et
 * {@code /fournisseurs/**} pour le gérant seulement (403 pour le caissier, 302 vers la connexion sans compte).</p>
 */
@Controller
public class RecuController {
    private final RecuService recus;

    public RecuController(RecuService recus) { this.recus = recus; }

    /** Reçu d'une vente : gérant et caissier. */
    @GetMapping("/ventes/recu/{id}")
    public ResponseEntity<byte[]> vente(@PathVariable Long id) {
        return pdf(recus.recuVente(id));
    }

    /** Reçu d'un règlement ultérieur d'une vente : gérant et caissier. */
    @GetMapping("/ventes/regler/{id}/recu/{mouvementId}")
    public ResponseEntity<byte[]> reglementVente(@PathVariable Long id, @PathVariable Long mouvementId) {
        return pdf(recus.recuReglementVente(id, mouvementId));
    }

    /** Reçu d'un paiement d'achat : gérant seulement, comme la page de règlement d'un achat. */
    @GetMapping("/achats/regler/{id}/recu/{mouvementId}")
    public ResponseEntity<byte[]> reglementAchat(@PathVariable Long id, @PathVariable Long mouvementId) {
        return pdf(recus.recuReglementAchat(id, mouvementId));
    }

    /** Reçu d'un règlement du solde de départ d'un client : gérant et caissier, comme la page de règlement. */
    @GetMapping("/clients/solde/regler/{id}/recu/{mouvementId}")
    public ResponseEntity<byte[]> reglementSoldeClient(@PathVariable Long id, @PathVariable Long mouvementId) {
        return pdf(recus.recuReglementSoldeClient(id, mouvementId));
    }

    /** Reçu d'un règlement du solde de départ d'un fournisseur : gérant seulement. */
    @GetMapping("/fournisseurs/solde/regler/{id}/recu/{mouvementId}")
    public ResponseEntity<byte[]> reglementSoldeFournisseur(@PathVariable Long id, @PathVariable Long mouvementId) {
        return pdf(recus.recuReglementSoldeFournisseur(id, mouvementId));
    }

    /** Ouverture dans le navigateur (inline), jamais mise en cache : le reçu contient des données de la boutique. */
    private static ResponseEntity<byte[]> pdf(RecuService.Recu recu) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(recu.pdf().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(recu.nomFichier()).build().toString())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .header("X-Content-Type-Options", "nosniff")
                .body(recu.pdf());
    }
}
