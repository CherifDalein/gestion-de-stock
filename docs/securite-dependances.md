# Mise à jour de Spring et mots de passe

Le projet utilise Spring Boot **3.5.16**, qui gère Spring Security **6.5.11** et Spring Framework **6.2.19**. Cette branche reste compatible avec Java 21, Gradle 8.7, Spring MVC, Thymeleaf et les tests existants. Les versions des composants sont choisies ensemble par Spring Boot, sans surcharge individuelle de Spring Security.

## Limite BCrypt

Le format des empreintes BCrypt existantes est conservé. Les mots de passe allant jusqu'à **72 octets en UTF-8** restent utilisables ; les accents et emojis peuvent occuper plusieurs octets. Les créations de compte dépassant cette limite sont refusées avec un message en français.

La bibliothèque Spring Security 6.5.11 refuse les nouveaux mots de passe trop longs lors du hachage, mais conserve la troncature pour vérifier les anciennes empreintes. La mise à jour seule ne supprime donc pas l'acceptation d'un suffixe après les 72 premiers octets. Le projet utilise `LengthCheckedBcryptPasswordEncoder` pour refuser explicitement ces saisies à la connexion, sans modifier les empreintes stockées.

La vérification BCrypt est exécutée avant le refus lié à la longueur. Cela conserve le travail de vérification effectué par `DaoAuthenticationProvider`, y compris contre une empreinte factice pour un compte absent. Les tentatives refusées suivent le parcours normal `/login?error`. Les tests ne constituent pas une mesure ni une certification des temps de réponse.

**Un compte dont le mot de passe initial dépassait déjà 72 octets devra recevoir un nouveau mot de passe conforme.** Une ancienne empreinte BCrypt ne permet pas de retrouver la longueur du mot de passe initial. Ce lot ne réinitialise aucun compte et n'ajoute pas de parcours de récupération de mot de passe.

## Vérification

```sh
./gradlew test bootJar
```

Les tests utilisent H2. Ils couvrent notamment la lecture d'empreintes produites avec Spring Security 6.2.4, la limite ASCII et UTF-8, le refus d'un suffixe par le vrai formulaire de connexion, les erreurs pour comptes existants et absents, la création de compte, les rôles, CSRF, les ventes, le stock concurrent et les règlements fournisseurs.

Un démarrage du fichier exécutable sur MariaDB a été vérifié avec `ddl-auto=validate`, des connexions en lecture seule et un port distinct. Aucun paiement ni modification de compte n'a été soumis sur cette base. Les paramètres usuels de démarrage restent décrits dans le README.

## Sources

- [Compatibilité Java et Gradle de Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Versions gérées par Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)
- [Avis Spring CVE-2025-22228](https://spring.io/security/cve-2025-22228/)
- [Avis Spring CVE-2025-22234](https://spring.io/security/cve-2025-22234/)
- [Vérification BCrypt dans Spring Security 6.5.11](https://github.com/spring-projects/spring-security/blob/6.5.11/crypto/src/main/java/org/springframework/security/crypto/bcrypt/BCrypt.java)
