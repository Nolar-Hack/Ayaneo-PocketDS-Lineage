# Dossier pour le support Ayaneo — Pocket DS (SM8550 / kalama, UFS)

## Problème
Après un flash EDL (firehose) du paquet public `AR11_FlatBuild_TurboX_C8550_xx.xx_LA1.0.D.user.20251012.065902`
(script `turbox_flat_flash.sh`, terminé sans erreur, 100 %), la console démarre sur le logo Snapdragon puis
retombe sur le menu bootloader (fastboot) **quel que soit le choix** : Start, Recovery mode, Boot to FFBM, Boot to QMMI.
Aucun Android, aucun recovery, aucun message d'erreur à l'écran.

## Ce qui fonctionne
- EDL 05c6:9008 (Vol+ & Vol−), Sahara + firehose `xbl_s_devprg_ns.melf`, écriture/lecture UFS OK.
- XBL démarre proprement à chaque tentative jusqu'à « SBL1, End » (journal `xbl_sc_logs`) : pas de plantage ni de watchdog, resets normaux (PSHOLD).
- Bootloader déverrouillé (`devinfo` : unlocked + unlock_critical), `secure: no`, `variant: SGP UFS`, `hw-revision: 20000`.
- Le compteur de tentatives du slot actif baisse : l'ABL essaie de démarrer puis abandonne.

## Ce qui a été essayé (sans effet)
- Flash officiel complet, avec et sans `persist`.
- `misc` remplie de zéros.
- ABL/UEFI du slot b recopiés dans le slot A ; firmware du slot b cloné dans A.
- Images `boot`, `init_boot`, `vendor_boot`, `dtbo`, `vbmeta`, `recovery` d'origine de la console (Android 13, kernel 5.15.104) dans les deux slots.
- Bascule du slot actif sur b via les attributs GPT, LUN d'amorçage UFS mise sur 2.
- Le résultat est identique sur A et sur b.

## Historique (cause probable)
La console tournait sous un Android modifié (slot b). Un `fastboot set_active a` a été exécuté sur un slot A incomplet, la console est restée
bloquée dans le bootloader, puis le flash EDL du paquet public a écrasé des partitions de démarrage dont il n'existe pas de sauvegarde :
`xbl`, `xbl_config`, `multiimg(qti/oem)`, `apdp`, `storsec`, `ddr`, `logfs`, `toolsfv`, `vm-persist`, `frp`.
Le firmware d'origine datait de 2026 (build `AYANEO/AYANEO_PocketDS/PocketDS:13/TKQ1.230811.002/eng.root.20261010.221510`), le paquet est du 2025-10-12.

## Demande
- Le firmware / outil de restauration EDL exact de la Pocket DS (variante UFS, `hw-revision 20000`), avec les composants de démarrage d'origine.
- Si possible, la procédure officielle pour rétablir l'état de l'ABL (menu FFBM/QMMI) après un flash complet.

## Éléments joints possibles
`scratchpad/edl/diag/` : `xbl_sc_logs.bin`, `devinfo.bin`, `uefivarstore.bin`, `gpt4_primary.bin`, `gpt4_backup.bin`, `abl_a.bin`, `abl_b.bin`, `uefi_a.bin`, `uefi_b.bin`,
et la sauvegarde Android `stock-backups/backup-20261016-200817` (images + SHA256SUMS).
