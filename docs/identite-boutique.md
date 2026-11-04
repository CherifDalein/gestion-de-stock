# Logo et coordonnées de la boutique

Le gérant retrouve ces réglages dans **Paramètres de la boutique** : nom
commercial, adresse, téléphone principal, deuxième téléphone et email.
Le nom est obligatoire ; les autres coordonnées peuvent être complétées
progressivement. Le deuxième numéro est facultatif.

Le champ **Logo de la boutique** accepte un fichier PNG ou JPEG de 2 Mo au
maximum. Une première importation ajoute le logo ; une nouvelle image le
remplace. Pour l'enlever, cocher **Supprimer le logo actuel** puis enregistrer. Choisir
une nouvelle image et demander son retrait simultanément affiche une erreur.
Un enregistrement sans fichier et sans retrait conserve le logo existant.

Les factures de vente, d'achat et les relevés cumulés des clients et
fournisseurs affichent le logo, le nom et les coordonnées de la boutique
connectée. Les deux numéros apparaissent ensemble lorsqu'ils sont renseignés.
L'image garde ses proportions, y compris à l'impression. Les informations
courantes sont utilisées lors de la réimpression d'anciens documents.

Seul le gérant modifie cette identité. Les caissiers peuvent charger le logo
de leur boutique pour les factures auxquelles ils ont accès. L'image est servie
par une route authentifiée, sans identifiant de boutique fourni par le client
et sans cache partagé entre comptes.

Le serveur vérifie le contenu du fichier et ses dimensions avant le décodage,
puis réencode l'image en PNG, dans un cadre maximal de 512 pixels, en conservant
la transparence. Il ne stocke pas le nom du fichier ou ses métadonnées. Un
fichier refusé ne change ni le logo existant ni les coordonnées enregistrées.
Le logo est conservé dans la base et inclus dans les sauvegardes SQL habituelles.

Les anciennes boutiques gardent toutes leurs coordonnées, sans logo ni
deuxième numéro ajoutés automatiquement. Pour une base existante, appliquer
la [migration de l'identité des boutiques](migrations/identite-boutique.md)
avant de démarrer cette version.
