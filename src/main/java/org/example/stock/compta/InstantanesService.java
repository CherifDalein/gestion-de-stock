package org.example.stock.compta;

import org.example.stock.enums.StatutInstantane;
import org.example.stock.model.ExerciceComptable;
import org.example.stock.model.InstantaneDocument;
import org.example.stock.model.InstantaneExercice;
import org.example.stock.repository.InstantaneDocumentRepository;
import org.example.stock.repository.InstantaneExerciceRepository;
import org.example.stock.service.BoutiqueService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Lecture des instantanés d'exercices (étape 5c) : contenu figé, documents, vérification de l'empreinte. L'écriture est dans {@link ClotureService}. */
@Service
@Transactional(readOnly = true)
public class InstantanesService {
    private final ExercicesService exercices;
    private final InstantaneExerciceRepository instantanes;
    private final InstantaneDocumentRepository documents;
    private final BoutiqueService boutiques;

    public InstantanesService(ExercicesService exercices, InstantaneExerciceRepository instantanes, InstantaneDocumentRepository documents, BoutiqueService boutiques) {
        this.exercices = exercices; this.instantanes = instantanes; this.documents = documents; this.boutiques = boutiques;
    }

    /** L'instantané actif et son contenu. */
    public record Figee(InstantaneExercice instantane, ContenuInstantane contenu) {}

    /** L'instantané actif de l'exercice dont les dates sont exactement {@code du} et {@code au}, s'il est clôturé. */
    public Optional<Figee> actif(Long boutiqueId, LocalDate du, LocalDate au) {
        Optional<ExercicesService.Exercice> e = exercices.exact(boutiqueId, du, au);
        if (e.isEmpty() || !e.get().estCloture()) return Optional.empty();
        return instantanes.findByExerciceIdAndStatut(e.get().ligne().getId(), StatutInstantane.ACTIF).map(i -> new Figee(i, ContenuInstantane.depuis(i.getContenu())));
    }

    /** Historique des instantanés de la boutique courante (actifs et annulés), du plus ancien exercice au plus récent. */
    public List<InstantaneExercice> historique() { return instantanes.findByBoutiqueIdOrderByDateDebutAscVersionAsc(boutiques.idBoutiqueCourante()); }

    public InstantaneExercice trouver(Long id) {
        return instantanes.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Instantané introuvable"));
    }

    public List<InstantaneDocument> documentsDe(Long instantaneId) { return documents.findByInstantaneIdOrderById(instantaneId); }

    public InstantaneDocument document(Long instantaneId, String code) {
        trouver(instantaneId);
        return documents.findByInstantaneIdAndCode(instantaneId, code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
    }

    /** Le document figé de l'instantané, ou vide s'il n'existe pas pour ce code. */
    @Transactional(readOnly = true)
    public Optional<InstantaneDocument> documentOuAucun(Long instantaneId, String code) { return documents.findByInstantaneIdAndCode(instantaneId, code); }

    /** Vrai si le contenu et les documents enregistrés donnent toujours l'empreinte enregistrée à la clôture. */
    public boolean empreinteConforme(InstantaneExercice i) {
        return i.getEmpreinte().equals(empreinte(i.getContenu(), documents.findByInstantaneIdOrderById(i.getId())));
    }

    /** SHA-256 (hexadécimal) du contenu, puis de chaque document par code : son code, son PDF et son Excel. */
    public static String empreinte(byte[] contenu, List<InstantaneDocument> documents) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("RFC-COMPTA-INSTANTANE-1\n".getBytes(StandardCharsets.UTF_8));
            sha.update(contenu);
            for (InstantaneDocument d : documents.stream().sorted(Comparator.comparing(InstantaneDocument::getCode)).toList()) {
                sha.update(("\n" + d.getCode() + "\n").getBytes(StandardCharsets.UTF_8));
                sha.update(d.getPdf());
                sha.update((byte) '\n');
                sha.update(d.getExcel());
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    public ExerciceComptable exercice(InstantaneExercice i) { return i.getExercice(); }
}
