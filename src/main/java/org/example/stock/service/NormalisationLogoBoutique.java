package org.example.stock.service;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/** Limites contrôlées avant décodage ; le résultat ne conserve aucune métadonnée fournie. */
@Component
public class NormalisationLogoBoutique {
    public static final int TAILLE_MAXIMALE = 2 * 1024 * 1024;
    // Le dump représente les octets en hexadécimal : garder sa restauration sous le paquet MariaDB local.
    public static final int TAILLE_STOCKEE_MAXIMALE = 384 * 1024;
    private static final int DIMENSION_MAXIMALE = 4096;
    private static final long PIXELS_MAXIMAUX = 4_000_000;
    private static final int DIMENSION_STOCKEE = 512;
    private static final byte[] SIGNATURE_PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] FIN_PNG = {0, 0, 0, 0, 73, 69, 78, 68, (byte) 174, 66, 96, (byte) 130};

    public byte[] normaliser(MultipartFile fichier) {
        if (fichier == null || fichier.isEmpty()) return null;
        if (fichier.getSize() > TAILLE_MAXIMALE) throw tailleInvalide();
        try (var entree = fichier.getInputStream()) {
            byte[] donnees = entree.readNBytes(TAILLE_MAXIMALE + 1);
            if (donnees.length > TAILLE_MAXIMALE) throw tailleInvalide();
            if (donnees.length == 0) throw invalide();
            boolean png = donnees.length >= 20 && Arrays.equals(SIGNATURE_PNG, Arrays.copyOf(donnees, 8));
            boolean jpeg = donnees.length >= 4 && (donnees[0] & 255) == 255
                    && (donnees[1] & 255) == 216 && (donnees[2] & 255) == 255;
            if (!png && !jpeg) throw formatInvalide();
            if (png && !Arrays.equals(FIN_PNG, Arrays.copyOfRange(donnees, donnees.length - 12, donnees.length))) throw invalide();
            if (jpeg && ((donnees[donnees.length - 2] & 255) != 255 || (donnees[donnees.length - 1] & 255) != 217)) throw invalide();
            try (var imageEntree = new MemoryCacheImageInputStream(new ByteArrayInputStream(donnees))) {
                var lecteurs = ImageIO.getImageReaders(imageEntree);
                if (!lecteurs.hasNext()) throw invalide();
                ImageReader lecteur = lecteurs.next();
                try {
                    String format = lecteur.getFormatName().toLowerCase(Locale.ROOT);
                    if (!(png && format.equals("png")) && !(jpeg && (format.equals("jpeg") || format.equals("jpg")))) throw formatInvalide();
                    lecteur.setInput(imageEntree, true, true);
                    int largeur = lecteur.getWidth(0), hauteur = lecteur.getHeight(0);
                    if (largeur <= 0 || hauteur <= 0 || largeur > DIMENSION_MAXIMALE || hauteur > DIMENSION_MAXIMALE
                            || (long) largeur * hauteur > PIXELS_MAXIMAUX) {
                        throw new LogoBoutiqueInvalideException("Le logo est trop grand : au maximum 4 millions de pixels et 4 096 pixels par côté.");
                    }
                    boolean[] avertissement = {false};
                    lecteur.addIIOReadWarningListener((source, message) -> avertissement[0] = true);
                    BufferedImage image = lecteur.read(0);
                    if (image == null || avertissement[0]) throw invalide();
                    try {
                        double facteur = Math.min(1d, (double) DIMENSION_STOCKEE / Math.max(largeur, hauteur));
                        while (true) {
                            int cibleLargeur = Math.max(1, (int) Math.round(largeur * facteur));
                            int cibleHauteur = Math.max(1, (int) Math.round(hauteur * facteur));
                            BufferedImage normalisee = new BufferedImage(cibleLargeur, cibleHauteur, BufferedImage.TYPE_INT_ARGB);
                            var dessin = normalisee.createGraphics();
                            try {
                                dessin.setComposite(AlphaComposite.Src);
                                dessin.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                                dessin.drawImage(image, 0, 0, cibleLargeur, cibleHauteur, null);
                            } finally { dessin.dispose(); }
                            try (var sortie = new ByteArrayOutputStream()) {
                                if (!ImageIO.write(normalisee, "png", sortie)) throw invalide();
                                if (sortie.size() <= TAILLE_STOCKEE_MAXIMALE) return sortie.toByteArray();
                            } finally { normalisee.flush(); }
                            if (cibleLargeur == 1 && cibleHauteur == 1) throw invalide();
                            // Repartir de l'original évite de cumuler les pertes de rééchantillonnage.
                            facteur *= .75d;
                        }
                    } finally { image.flush(); }
                } finally { lecteur.dispose(); }
            }
        } catch (LogoBoutiqueInvalideException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalide();
        }
    }

    private static LogoBoutiqueInvalideException invalide() {
        return new LogoBoutiqueInvalideException("Le logo est illisible ou incomplet. Choisissez une image PNG ou JPEG valide.");
    }
    private static LogoBoutiqueInvalideException formatInvalide() {
        return new LogoBoutiqueInvalideException("Le logo doit être une image PNG ou JPEG.");
    }
    private static LogoBoutiqueInvalideException tailleInvalide() {
        return new LogoBoutiqueInvalideException("Le logo accepte au maximum 2 Mo.");
    }
}
