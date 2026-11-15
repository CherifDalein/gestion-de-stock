package org.example.stock.compta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.stock.compta.ComptabiliteService.Balance;
import org.example.stock.compta.EtatsFinanciersCalcul.Exercice;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Montants calculés d'un exercice au moment de sa clôture : les états de l'exercice (compte de résultat, bilan, flux, SMT), ceux de l'exercice précédent
 * affichés en regard, et la balance définitive. Ils sont enregistrés tels quels (JSON) : une règle de calcul qui change plus tard ne modifie jamais un
 * instantané.
 *
 * @param format          numéro de version du format (1)
 * @param exercicePrecedent états de l'exercice N-1 (colonne de comparaison), ou {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContenuInstantane(int format, LocalDate du, LocalDate au, String systeme, Exercice exercice, Exercice exercicePrecedent, Balance balance) {
    public static final int FORMAT = 1;

    private static final JsonMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public byte[] enOctets() {
        try { return JSON.writeValueAsBytes(this); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new UncheckedIOException(e); }
    }

    public static ContenuInstantane depuis(byte[] octets) {
        try { return JSON.readValue(new String(octets, StandardCharsets.UTF_8), ContenuInstantane.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Instantané illisible : le contenu enregistré n'a pas le format attendu.", e); }
    }
}
