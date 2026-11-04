package org.example.stock.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalisationLogoBoutiqueTests {
    private final NormalisationLogoBoutique service = new NormalisationLogoBoutique();

    @Test
    void unPngGardeSaTransparenceEtPerdLesMetadonnees() throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x00ff0000); image.setRGB(1, 0, 0x800000ff);
        var ecrivain = ImageIO.getImageWritersByFormatName("png").next();
        byte[] source;
        try (var sortie = new ByteArrayOutputStream(); var flux = new MemoryCacheImageOutputStream(sortie)) {
            ecrivain.setOutput(flux);
            var metadonnees = ecrivain.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), null);
            var racine = new IIOMetadataNode("javax_imageio_png_1.0");
            var texte = new IIOMetadataNode("tEXt"); var valeur = new IIOMetadataNode("tEXtEntry");
            valeur.setAttribute("keyword", "Comment"); valeur.setAttribute("value", "metadonnee-personnelle-a-retirer");
            texte.appendChild(valeur); racine.appendChild(texte); metadonnees.mergeTree("javax_imageio_png_1.0", racine);
            ecrivain.write(null, new IIOImage(image, null, metadonnees), null); flux.flush(); source = sortie.toByteArray();
        } finally { ecrivain.dispose(); }
        assertThat(new String(source, StandardCharsets.ISO_8859_1)).contains("metadonnee-personnelle-a-retirer");
        byte[] resultat = service.normaliser(fichier("image/png", source));
        BufferedImage relue = ImageIO.read(new ByteArrayInputStream(resultat));
        assertThat(relue.getWidth()).isEqualTo(2); assertThat(relue.getHeight()).isOne();
        assertThat(relue.getRGB(0, 0) >>> 24).isZero(); assertThat(relue.getRGB(1, 0) >>> 24).isEqualTo(128);
        assertThat(new String(resultat, StandardCharsets.ISO_8859_1)).doesNotContain("metadonnee-personnelle-a-retirer");
    }

    @Test
    void unJpegEstRedimensionneEnPngEnGardantLesProportions() throws Exception {
        BufferedImage image = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
        image.setRGB(100, 100, 0xff112233);
        byte[] resultat = service.normaliser(fichier("image/jpeg", encoder(image, "jpeg")));
        assertThat(Arrays.copyOf(resultat, 8)).containsExactly((byte) 137, (byte) 80, (byte) 78, (byte) 71, (byte) 13, (byte) 10, (byte) 26, (byte) 10);
        BufferedImage relue = ImageIO.read(new ByteArrayInputStream(resultat));
        assertThat(relue.getWidth()).isEqualTo(512); assertThat(relue.getHeight()).isEqualTo(256);
        assertThat(relue.getRGB(0, 0) >>> 24).isEqualTo(255);
    }

    @Test
    void leContenuCompteEtNonLeNomOuLeMimeFournis() throws Exception {
        byte[] png = encoder(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png");
        assertThat(service.normaliser(new MockMultipartFile("logo", "../../image.svg", "text/html", png))).isNotEmpty();
        assertThatThrownBy(() -> service.normaliser(fichier("image/png", "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(LogoBoutiqueInvalideException.class);
    }

    @Test
    void unLogoRgbaPeuCompressibleEstOptimisePourLEnregistrementEtLaSauvegardeSql() throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Random hasard = new Random(20261104);
        for (int y=0; y<512; y++) for (int x=0; x<512; x++) image.setRGB(x,y,hasard.nextInt());
        byte[] source = encoder(image, "png");
        assertThat(source.length).isGreaterThan(NormalisationLogoBoutique.TAILLE_STOCKEE_MAXIMALE)
                .isLessThanOrEqualTo(NormalisationLogoBoutique.TAILLE_MAXIMALE);
        byte[] resultat = service.normaliser(fichier("image/png", source));
        assertThat(resultat.length).isLessThanOrEqualTo(NormalisationLogoBoutique.TAILLE_STOCKEE_MAXIMALE);
        BufferedImage relue = ImageIO.read(new ByteArrayInputStream(resultat));
        assertThat(relue).isNotNull();
        assertThat(relue.getWidth()).isBetween(1,512); assertThat(relue.getHeight()).isEqualTo(relue.getWidth());
        assertThat(relue.getWidth()).isLessThan(512);
        assertThat(relue.getColorModel().hasAlpha()).isTrue();
        assertThat(relue.getRGB(relue.getWidth()/2,relue.getHeight()/2) >>> 24).isLessThan(255);
    }

    @Test
    void uneAbsenceDeFichierNeDemandePasDEffacement() {
        assertThat(service.normaliser(null)).isNull();
        assertThat(service.normaliser(fichier("application/octet-stream", new byte[0]))).isNull();
    }

    @Test
    void unFichierTropLourdEstRefuseAvantLeDecodage() {
        byte[] tropGrand = new byte[NormalisationLogoBoutique.TAILLE_MAXIMALE + 1];
        assertThatThrownBy(() -> service.normaliser(fichier("image/png", tropGrand))).hasMessageContaining("2 Mo");
    }

    @Test
    void leFluxResteBorneMemeSiLaTailleDeclareeEstFausse() {
        var fichier = new MockMultipartFile("logo", "image.png", "image/png", new byte[NormalisationLogoBoutique.TAILLE_MAXIMALE + 1]) {
            @Override public long getSize() { return 1; }
        };
        assertThatThrownBy(() -> service.normaliser(fichier)).hasMessageContaining("2 Mo");
    }

    @ParameterizedTest
    @ValueSource(ints = {50000, 3000})
    void lesDimensionsSontControleesAvantAllocationDesPixels(int largeur) throws Exception {
        byte[] png = encoder(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png");
        ByteBuffer.wrap(png).putInt(16, largeur).putInt(20, 2000);
        CRC32 crc = new CRC32(); crc.update(png, 12, 17); ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        assertThatThrownBy(() -> service.normaliser(fichier("image/png", png)))
                .isInstanceOf(LogoBoutiqueInvalideException.class).hasMessageContaining("millions de pixels");
    }

    @Test
    void unGifEstRefuseMemeSousUnNomPng() throws Exception {
        byte[] gif = encoder(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "gif");
        assertThatThrownBy(() -> service.normaliser(fichier("image/png", gif))).hasMessageContaining("PNG ou JPEG");
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    void uneImageTronqueeEstRefusee(String format) throws Exception {
        byte[] image = encoder(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format);
        assertThatThrownBy(() -> service.normaliser(fichier("image/" + format, Arrays.copyOf(image, image.length - 1))))
                .isInstanceOf(LogoBoutiqueInvalideException.class).hasMessageContaining("incomplet");
    }

    private static MockMultipartFile fichier(String mime, byte[] contenu) {
        return new MockMultipartFile("logo", "logo.png", mime, contenu);
    }
    private static byte[] encoder(BufferedImage image, String format) throws Exception {
        try (var sortie = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, format, sortie)).isTrue(); return sortie.toByteArray();
        }
    }
}
