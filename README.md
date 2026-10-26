# My Networks

Carnet d'adresses personnel, local et privé pour Android. Personnes, entreprises, compétences, catégories et emplacements y forment une carte en forme de réseau neuronal. On y retrouve qui contacter et pourquoi, et on peut appeler directement.

- **Local uniquement** : pas de compte, pas de serveur, pas de synchronisation, pas de pub, pas de télémétrie. L'APK **ne possède pas la permission `INTERNET`**, ce que la CI vérifie à chaque build.
- **Chiffré au repos** : la base SQLCipher (AES-256) et les photos (AES-256-GCM) sont protégées par une clé Android Keystore.
- **Un seul écran** : le graphe. Recherche, ajout, fiches et réglages s'ouvrent en panneaux superposés.

## Sommaire
1. [Installer sur le Samsung Galaxy A36](#installer-sur-le-samsung-galaxy-a36)
2. [Architecture](#architecture)
3. [Construction dans le cloud](#construction-dans-le-cloud)
4. [Signature et mises à jour](#signature-et-mises-à-jour)
5. [Permissions](#permissions)
6. [Confidentialité, chiffrement et limites](#confidentialité-chiffrement-et-limites)
7. [Sauvegarde et changement de téléphone](#sauvegarde-et-changement-de-téléphone)
8. [Regroupements du réseau](#regroupements-du-réseau)
9. [Tests et mesures](#tests-et-mesures)
10. [Limites connues et prochaines étapes](#limites-connues-et-prochaines-étapes)

## Installer sur le Samsung Galaxy A36

1. Sur le téléphone, ouvre la page **Releases** du dépôt GitHub dans le navigateur et télécharge le fichier `my-networks-….apk`. Tu peux aussi le copier depuis un ordinateur par câble USB, dans le dossier *Téléchargements*.
2. Ouvre l'application **Mes fichiers** (Samsung), puis *Téléchargements*, et touche le fichier APK.
3. Si Android affiche « Pour votre sécurité, votre téléphone n'est pas autorisé à installer des applications inconnues de cette source », touche **Paramètres**. Active **Autoriser depuis cette source** pour l'application utilisée (*Mes fichiers*, ou ton navigateur), puis reviens en arrière et touche **Installer**.
   - Ce réglage se retrouve aussi dans *Paramètres → Applications → (menu ⋮) Accès spécial → Installer des applis inconnues*. Tu peux le désactiver après l'installation.
4. **Play Protect** peut afficher « Application non vérifiée ». Choisis *Plus de détails → Installer quand même* **pour cette application uniquement**. Ne désactive pas Play Protect.
5. **Auto Blocker** (Samsung, Android 15+) bloque les installations hors Galaxy Store et Play Store. Si l'installation est refusée, désactive-le temporairement dans *Paramètres → Sécurité et confidentialité → Auto Blocker*, installe, puis réactive-le.

Pour vérifier le fichier, son empreinte SHA-256 est indiquée dans les notes de chaque release.

**Mettre à jour** : installe le nouvel APK par-dessus l'ancien, sans désinstaller. Les données sont conservées tant que l'APK est signé avec la même clé.

## Architecture

Kotlin, Jetpack Compose, Room sur SQLCipher, coroutines. Il n'y a aucune bibliothèque réseau, d'analyse ou d'IA.

```
app/src/main/java/ch/m4rm0tt/mynetworks/
├── domain/          Logique pure, testée sur JVM
│   ├── Model.kt          Personne / Entreprise / Moi, valeurs communes, champs de carte
│   ├── GraphLayout.kt    Disposition du réseau (déterministe, O(n log n))
│   ├── TextNorm.kt       Recherche sans casse ni accents, comparaison des valeurs
│   └── PhoneNumbers.kt   Format international pour WhatsApp, sans deviner le pays
├── data/            Stockage local
│   ├── db/               Room : fiches, valeurs communes, associations, liens manuels
│   ├── NetworkRepository Enregistrement transactionnel, recherche, renommage des valeurs
│   ├── PhotoStore        Photos recadrées, réduites et chiffrées dans l'espace privé
│   └── SettingsStore     Préférences non sensibles
├── security/KeyManager   Secrets aléatoires enveloppés par Android Keystore
├── backup/          Export/import chiffré par mot de passe
├── ui/
│   ├── graph/NetworkGraph   Carte sur un seul Canvas : culling, niveaux de détail, gestes
│   ├── components/          Boutons « verre liquide », panneaux, pictogrammes dessinés
│   ├── sheets/              Carte de visite, Informations, formulaire, recherche, réglages
│   └── MainViewModel        État de l'écran unique (disposition calculée hors thread UI)
└── MainActivity             Verrouillage biométrique, appel, WhatsApp, liens
```

Choix principaux :
- **Graphe sur un seul `Canvas`**, sans un composant par nœud ni WebView. Le calcul de la disposition (`domain`), le stockage (`data`) et le rendu (`ui/graph`) sont séparés.
- **Pas d'injection de dépendances ni de navigation** : il n'y a qu'un écran et quelques objets, ce qui rend inutile une architecture plus lourde.
- **Pictogrammes dessinés en code**, sans police d'icônes et sans ressource distante.

Versions figées dans `gradle/libs.versions.toml` : AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.0, Compose BOM 2024.12.01, Room 2.6.1, SQLCipher 4.6.1, Biometric 1.1.0. Ce ne sont pas les dernières versions (AGP 9.x existe), mais une combinaison stable et cohérente. La montée de version est une étape à part (voir plus bas).

- `minSdk 26` (Android 8), `targetSdk 35`. Le Galaxy A36 est sorti sous Android 15 et est donc compatible.

## Construction dans le cloud

Rien n'est à installer en local. Le workflow `.github/workflows/android.yml` :
1. exécute les tests unitaires JVM ;
2. compile l'APK release (R8, ressources réduites) et le signe avec la clé durable fournie par les secrets ;
3. vérifie la signature (`apksigner`), calcule le SHA-256 et **échoue si `INTERNET` apparaît dans les permissions** ;
4. publie l'APK comme artefact (conservé 30 jours) et, sur demande, dans une **Release** GitHub, qui elle n'expire pas.

Lancement manuel : onglet *Actions → Android → Run workflow*. Coche `release` pour publier, et `emulator` pour lancer les tests instrumentés et prendre des captures sur émulateur. Les captures et mesures sont alors poussées sur la branche `ci-artifacts`.

## Signature et mises à jour

- La clé de release (`my-networks-release.jks`, RSA 4096) est générée **une seule fois**. Elle n'est **pas** dans Git (`.gitignore` exclut `*.jks`).
- La CI la reçoit par 4 secrets GitHub : `MYNET_KEYSTORE_B64`, `MYNET_KEYSTORE_PASSWORD`, `MYNET_KEY_ALIAS`, `MYNET_KEY_PASSWORD`.
- **Garde une copie hors ligne** de la clé et de son mot de passe (gestionnaire de mots de passe, clé USB chiffrée). Les secrets GitHub ne sont pas relisibles : sans ta copie, une clé perdue est perdue. Sans elle, aucune mise à jour ne pourra s'installer par-dessus. Il faudrait désinstaller l'application, donc exporter le réseau avant.
- Une signature de **debug** est régénérée sur chaque machine et ne permet pas les mises à jour. La CI ne l'utilise pas pour les APK publiés. Sans secrets, l'APK release sort **non signé** et un avertissement le signale.
- `versionCode` = numéro du run CI, toujours croissant.

## Permissions

| Permission | Pourquoi | Quand |
|---|---|---|
| `CALL_PHONE` | Lancer l'appel directement depuis la fiche | Demandée au premier appui sur « Appeler », avec une explication. En cas de refus, le numéro s'ouvre dans le composeur et la demande n'est pas répétée en boucle |

Aucune autre permission : pas de contacts, SMS, journal d'appels, micro, caméra, localisation, stockage partagé ni Internet. Les photos passent par le **sélecteur de photos Android**, qui ne donne aucun accès général à la galerie. L'emplacement d'une fiche est un texte libre, jamais une position GPS.

WhatsApp, l'appel, les e-mails et les liens ouvrent **d'autres applications** sur ton appui explicite. Ces applications ont leurs propres besoins réseau et conditions opérateur. My Networks n'envoie jamais de message automatiquement.

## Confidentialité, chiffrement et limites

Ce qui est implémenté :
- **Base** : SQLCipher 4 (AES-256). La phrase secrète fait 32 octets aléatoires et est chiffrée (AES-256-GCM) par une clé maître **non exportable** d'Android Keystore. Elle est stockée dans `noBackupFilesDir`.
- **Photos** : copiées, recadrées à 640 px (miniatures de 160 px) et chiffrées en AES-256-GCM avec une seconde clé, protégée de la même façon.
- **Sauvegardes Android** : `allowBackup="false"`, et les règles `fullBackupContent` / `dataExtractionRules` excluent tout, cloud **et** transfert d'appareil à appareil. Le passage à un nouveau téléphone se fait donc par l'export chiffré.
- **Aperçu masqué** dans les applications récentes (`setRecentsScreenshotEnabled(false)` sur Android 13+, `FLAG_SECURE` en dessous si le verrouillage est actif).
- **Verrouillage** : `BiometricPrompt` avec biométrie de classe 2 ou 3 (empreinte, visage selon ce que Samsung expose), ou code de l'appareil en repli. Re-verrouillage immédiat, après 1 min ou après 5 min en arrière-plan.
- **Migrations non destructives** : aucune `fallbackToDestructiveMigration`. Si la clé est introuvable, rien n'est effacé : un écran l'explique. Repartir à zéro demande une confirmation, et les anciens fichiers sont **déplacés**, pas supprimés.
- Aucune donnée personnelle n'est écrite dans les logs.

Limites honnêtes :
- **Le verrouillage biométrique est une barrière d'interface**, pas la source du chiffrement. La clé maître n'exige pas d'authentification, pour qu'un changement d'empreinte ou de visage ne la détruise pas. Quelqu'un qui contrôle le téléphone déverrouillé avec des outils d'extraction avancés (root) pourrait utiliser la clé Keystore. Le chiffrement protège surtout contre la copie des fichiers et les sauvegardes.
- **Reconnaissance faciale** : sur Samsung, elle est en général de classe 2. Elle est acceptée ici parce que le verrouillage ne déverrouille pas de clé. Selon le modèle et la version, Android peut ne proposer que l'empreinte.
- **Pendant l'utilisation**, les données déchiffrées sont en mémoire. Les mots de passe saisis dans Compose sont des `String`, qu'on ne peut pas effacer de la mémoire.
- Une **réinitialisation de la sécurité** ou certains changements système peuvent invalider la clé Keystore. Les données locales deviennent alors illisibles et seul un export récent permet de les récupérer. **Fais des exports réguliers.**

## Sauvegarde et changement de téléphone

*Réglages → Sauvegarde* :
- **Exporter** crée un fichier `.mnbk` via le sélecteur de fichiers Android. Le mot de passe fait au moins 10 caractères et n'est stocké nulle part. Le fichier contient les fiches, les valeurs communes, les associations, les liens manuels, les photos et les préférences.
- **Format** : conteneur chiffré construit avec des primitives standard (`backup/BackupCrypto.kt`).
  - La clé est dérivée par **PBKDF2-HMAC-SHA256** (600 000 itérations, sel aléatoire).
  - Les données sont chiffrées en **AES-256-GCM par segments de 64 Kio** selon la construction STREAM (la même idée que Tink ou age), avec un en-tête authentifié et versionné.
  - Un vérificateur distingue un mauvais mot de passe d'un fichier corrompu.
  - Toute modification, inversion ou troncature est détectée.
  - Le fichier ne dépend pas de la clé matérielle du téléphone.
- **Importer** déchiffre et vérifie tout le fichier **avant** de toucher la base. Les photos transitent rechiffrées avec la clé du téléphone, puis l'écriture se fait en une seule transaction. Un mauvais mot de passe ou un fichier corrompu laisse la base intacte.
  - **Fusionner** ajoute les fiches. Pour une fiche présente des deux côtés, la version la plus récente est gardée, et les réglages ne changent pas.
  - **Tout remplacer** demande une confirmation, puis remplace aussi les réglages (sauf le verrouillage, propre à chaque appareil).

## Regroupements du réseau

- Je suis au centre, avec le plus gros nœud. Toucher ce nœud ouvre ma carte (mini-CV). Rien n'y est inventé : elle ne montre que les champs remplis.
- Critère au choix dans *Réglages* : catégorie, compétence, emplacement, métier, entreprise, ou aucun.
- Un **groupe** n'existe que si **au moins 2 fiches** partagent la même valeur.
- Une fiche qui appartient à plusieurs groupes est **dessinée une seule fois**, dans le plus grand (à égalité, l'ordre alphabétique décide). Un trait discret la relie à ses autres groupes, et toutes ses représentations ouvrent la même fiche.
- Les fiches sans groupe orbitent autour de moi. Les groupes sont triés par nom sur un anneau.
- **Aucun lien n'est créé entre deux personnes**. Les traits vont toujours vers un groupe ou vers moi.
- **Favoris** : un lien jaune « légendaire » me relie à eux, avec une étoile sur le nœud pour ne pas dépendre de la couleur seule.
- Les valeurs sont comparées sans tenir compte de la casse ni des espaces superflus (« musique » = « Musique »). Il n'y a aucune fusion sémantique : « Vevey » et « Riviera » restent distincts. *Réglages → Valeurs communes* permet de renommer : donner à une valeur le nom d'une autre réunit les deux.
- Les filtres Personnes, Entreprises et Favoris seulement se cumulent. Un résultat de recherche caché par un filtre est révélé temporairement.

Gestes : glisser à un doigt pour se déplacer, pincer pour zoomer, double-toucher pour zoomer sur un point, toucher un groupe pour s'en approcher. Au zoom faible, les membres sont résumés par le nœud de groupe et son compteur. Les photos et les noms apparaissent en zoomant.

## Tests et mesures

- **Tests JVM** (`app/src/test`), lancés à chaque build : disposition (pas de chevauchement à 10, 100 et 1 000 fiches, déterminisme, stabilité, absence de lien entre personnes, 10 000 fiches), recherche sans accents, numéros, conteneur chiffré (aller-retour, mauvais mot de passe, octet modifié, troncature).
- **Tests instrumentés** (`app/src/androidTest`, sur émulateur via la CI) :
  - persistance après réouverture, fichier de base illisible en clair, refus d'une mauvaise clé ;
  - recherche accents et casse, mise à jour après modification ;
  - valeurs réunies au renommage, entreprise rattachée sans doublon ;
  - export puis import avec photos, mauvais mot de passe, fichier corrompu, fusion ;
  - captures réelles de l'application et mesures à 100, 1 000 et 10 000 fiches **fictives**.
- Les mesures sur émulateur (rendu logiciel, sans GPU) **ne représentent pas** la fluidité sur le Galaxy A36. Seul un essai sur le téléphone fait foi.

## Limites connues et prochaines étapes

- Liens manuels entre fiches : le modèle de données est prêt, mais il n'y a pas encore d'interface.
- Rotation : l'état (zoom, panneaux, saisie en cours) survit. Après un arrêt du processus par Android, la position du graphe est recalculée.
- Avant Android 9, l'orientation EXIF des photos n'est pas corrigée (non concerné sur le A36).
- La montée de version vers AGP 9 et des bibliothèques plus récentes sera faite dans une étape dédiée et testée.
- À vérifier sur le téléphone : la checklist de validation se trouve dans la release.
