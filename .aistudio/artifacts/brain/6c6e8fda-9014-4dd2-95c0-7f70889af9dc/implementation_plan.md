# SIGNER — HephaistOS Signature & Keyset Engine

Application Android native conçue pour l'écosystème HephaistOS, dédiée à la signature cryptographique d'APKs, de partitions (`.img`), de GSI et de ROMs complètes (custom/stock) avec régénération de chaîne de confiance (AVB/vbmeta) et génération de keysets AOSP complets.

---

### Décisions Validées & Paramètres Utilisateur

- **Style Visuel & Palette Material 3** : Adaptatif et dynamique, s'harmonisant immédiatement avec l'application hôte et le système HephaistOS (support Dynamic Colors M3, mode sombre profond OLED et accents contrastés).
- **Rôle de l'Analyse Cryptographique** : Analyse d'intégrité déterministe et locale haute performance (inspection des signatures v1/v2/v3/v4, ELF, odex/vdex/oat, fsvmeta, et permissions XML) sans dépendance cloud bloquante, assurant rapidité et résilience offline.
- **Profil Cryptographique Keymaker** : Keyset AOSP complet (platform, media, shared, releasekey, avb 4096-bit, testkey, networkstack) avec certificats X.509 (`.x509.pem`), clés privées PKCS#8 (`.pk8`), paires de clés AVB (`.pem`/`.bin`) et structure normalisée.
- **Chemins de Données HephaistOS** :
  - Métadonnées & diagnostics d'analyse ROM : `/sdcard/HephaistOS/Data/Signer/`
  - Keyset et paires de clés : `/sdcard/HEPHAISTOS/Data/keyset/`
  - Sorties d'APKs résignés : `/sdcard/HEPHAISTOS/SIGNER/APP/`
  - Sorties de ROMs/GSI résignés : `/sdcard/HEPHAISTOS/SIGNER/ROM/`

---

## 1. Vue d'Ensemble & Capacités Clés

SIGNER constitue le module cryptographique officiel de HephaistOS. Il permet aux moddeurs, développeurs et mainteneurs d'OS de resigner sans risque d'altération ni de bootloop :

1. **Keymaker** : Générateur cryptographique de trousseaux complets AOSP (`platform`, `media`, `shared`, `releasekey`, `networkstack`, `avb/vbmeta`). Personnalisation du Subject X.509 (CN, OU, O, L, ST, C, validité), génération RSA 2048/4096 bits et export aux formats standards AOSP (`.pk8`, `.x509.pem`, `.pem`, `.bin`).
2. **Appsigner** : Sélection et signature d'APKs isolés ou par lots, avec sélection directe de fichiers ou extraction à la volée depuis une ROM/GSI sélectionnée. Détection automatique des schémas de signature (v1 JAR, v2 Full APK, v3 APK Signature Scheme v3, v4).
3. **Rom Signer (Moteur Principal)** :
   - Détection automatique du type de source : ROM Custom (zip), Stock ROM (payload.bin / sparse), image brute (`system.img`, `vendor.img`, `product.img`), ou GSI.
   - Analyse pré-signature approfondie : scan des packages système, distinction intelligente des applications signées par la clé plateforme vs applications tierces ou Google Apps (Gapps) à préserver, détection des conteneurs `odex`, `vdex`, `oat`, et signatures fs-verity (`fsvmeta`).
   - Journalisation & sauvegarde de l'audit complet dans `/sdcard/HephaistOS/Data/Signer/`.
   - Traitement cohérent avec régénération des fichiers d'association (`packages.xml`, `runtime-permissions.xml`, `privapp-permissions.xml`).
   - Mode signature standard & Mode **"Signer avec vbmeta génération"** recalculant la structure Android Verified Boot (AVB 2.0) pour maintenir la chaîne de confiance vérifiable au démarrage.

---

## 2. Expérience Utilisateur & Design Material 3

L'application adopte une ergonomie épurée, sans surcharge, fidèle à l'identité industrielle et soignée de HephaistOS :

```
┌────────────────────────────────────────────────────────┐
│  SIGNER                               HephaistOS V1.0  │
│  Moteur Cryptographique & Resignature d'OS             │
├────────────────────────────────────────────────────────┤
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 🔑  KEYMAKER                                      │  │
│  │     Génération de keyset AOSP & AVB complet      │  │
│  │     Sortie: /sdcard/HEPHAISTOS/Data/keyset/      │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 📦  APP SIGNER                                    │  │
│  │     Signature d'APKs isolés ou extraits d'une ROM │  │
│  │     Sortie: /sdcard/HEPHAISTOS/SIGNER/APP/        │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │ ⚡  ROM SIGNER                                    │  │
│  │     Signature complète de ROM, GSI ou Image      │  │
│  │     Analyse amont, préservation Gapps & vbmeta   │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  [ Indicateur Statut Stockage & Intégrité Système ]   │
└────────────────────────────────────────────────────────┘
```

- **Animations & Fluidité** : Transitions animées `AnimatedContent` et `Crossfade` entre le tableau de bord principal et les 3 pages de modules (`Keymaker`, `Appsigner`, `RomSigner`).
- **Support des Retours Système** : Intégration de `BackHandler` sur chaque sous-page pour une navigation fluide sans friction.
- **Thème M3 & Dynamic Colors** : Utilisation stricte des tokens Material 3 (`primary`, `surfaceContainer`, `outlineVariant`, `tonalElevation`), respect de la safe area bord-à-bord (`enableEdgeToEdge`) avec gestion des insets système (`WindowInsets.safeDrawing`).
- **Retours Tactiles & Visuels** : Rétroaction haptique, barres de progression linéaires et circulaires animées lors des étapes d'analyse, génération et injection des clés.

---

## 3. Architecture Technique & Moteur Cryptographique

```
┌────────────────────────────────────────────────────────────────────────┐
│                          SIGNER Architecture                           │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │
│  │  KeymakerScreen  │  │ AppSignerScreen  │  │   RomSignerScreen    │  │
│  └─────────┬────────┘  └────────┬─────────┘  └──────────┬───────────┘  │
│            │                    │                       │              │
│            ▼                    ▼                       ▼              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │
│  │ KeymakerViewModel│  │ AppSignerViewModel│ │  RomSignerViewModel  │  │
│  └─────────┬────────┘  └────────┬─────────┘  └──────────┬───────────┘  │
│            │                    │                       │              │
│            ▼                    ▼                       ▼              │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                    SignerCoreEngine                              │  │
│  │  - AospKeyGenerator (RSA 4096 / PK8 / X.509 PEM / AVB 2.0 Key)   │  │
│  │  - ApkSignerEngine (Signatures v1, v2, v3, cert replacement)     │  │
│  │  - RomInspectorEngine (Archive / Sparse / GSI / Context analyzer)│  │
│  │  - VbmetaGenerator (AVB descriptor recalculation & signing)       │  │
│  │  - StorageManager (sdcard/HEPHAISTOS/* data layout sync)         │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### Composants Techniques Clés :
1. **`AospKeyGenerator`** :
   - Utilise `java.security.KeyPairGenerator` (RSA 4096 et 2048 bits) et génération de certificats X.509 encodés DER/PEM avec Subject Distinguished Name configurable.
   - Encodage des clés privées au format PKCS#8 DER (`.pk8`) et paires AVB pour `vbmeta.img`.
2. **`ApkSignerEngine`** :
   - Traitement des APKs (fichiers ZIP conformes) : extraction, recalcul des digests SHA-256 des entrées ZIP, élimination propre des anciens blocs de signature (`META-INF/*.SF`, `*.RSA`, et `APK Signing Block`), injection du nouveau bloc v2/v3 et alignement zip 4-byte (zipalign).
3. **`RomInspectorEngine` & Analyse Amont** :
   - Analyse structurelle du conteneur (archive flashable ZIP, image raw/sparse, GSI).
   - Recensement des fichiers APK, détection des certificats existants pour séparer la clé système (à remplacer) des applications avec signature tierce (ex: Google Play Services / Gapps à conserver impérativement sous peine de crash).
   - Détection des fichiers dépendants des signatures : `.vdex`, `.odex`, `.art`, et métadonnées `fsvmeta`.
   - Export d'un rapport structuré JSON dans `/sdcard/HephaistOS/Data/Signer/audit_<timestamp>.json`.
4. **`VbmetaGenerator`** :
   - Modélisation de la structure d'en-tête AVB 2.0 (`AVB0` magic header, hash descriptors, chain partition descriptors).
   - Calcul des hashes d'intégrité des images modifiées et signature de la structure vbmeta avec la clé privée AVB générée.
5. **Préparation GitHub Actions CI** :
   - Fichier `.github/workflows/build.yml` complet pour compiler automatiquement l'APK debug et release sous Ubuntu avec JDK 17 et Gradle.

---

## 4. Plan de Réalisation Étape par Étape

1. **Configuration du Projet & Manifeste** :
   - Déclaration des permissions de stockage (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`).
   - Mise à jour de `metadata.json` ("SIGNER") et `strings.xml`.
   - Création du workflow GitHub Actions `.github/workflows/build.yml`.
2. **Moteur Cryptographique (`com.example.signer.core`)** :
   - `CryptoUtils` & `AospKeyGenerator` : génération des paires RSA, certificats X.509 et export PK8 / PEM / AVB.
   - `ApkSignerCore` : analyseur d'APK, détection des signatures, alignement et signature v1/v2.
   - `RomSignerCore` : inspecteur de ROM, gestionnaire d'arborescence, préservation Gapps, extraction et génération de vbmeta.
   - `HephaistosStorage` : gestion des dossiers `/sdcard/HEPHAISTOS/` et export des rapports d'audit.
3. **Architecture MVVM & États UI** :
   - `SignerDashboardViewModel` : gestion de la navigation et statut des répertoires.
   - `KeymakerViewModel` : paramètres des clés (CN, OU, taille de clé), exécution de la génération, log en temps réel.
   - `AppSignerViewModel` : sélection de fichier APK ou ROM, sélecteur d'APKs cibles, progression de signature.
   - `RomSignerViewModel` : sélection du fichier ROM/GSI/IMG, audit d'intégrité, mode signature standard vs mode signature avec vbmeta.
4. **Interface Utilisateur Jetpack Compose Material 3** :
   - Écran d'accueil principal (Dashboard HephaistOS) avec cartes d'accès direct animées.
   - Écran `KeymakerScreen` avec aperçu du trousseau, champs de saisie et exporteur.
   - Écran `AppSignerScreen` avec explorateur de packages et journal d'exécution.
   - Écran `RomSignerScreen` avec moniteur d'audit pré-signature, sélecteur d'options intelligentes et double déclencheur (Signature & Signature + vbmeta).
5. **Vérification & Compilation** :
   - Vérification du build avec `compile_applet`.
   - Validation de l'arborescence et des flux.
