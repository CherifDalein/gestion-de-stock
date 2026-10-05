#!/usr/bin/env bash
set -euo pipefail
umask 077

# Sauvegarde SQL de stock_pro sur le serveur XAMPP local uniquement.
# Arrêter l'application et éviter toute modification du schéma pendant le dump.
# Les routines et événements sont omis : leur lecture échoue si les tables
# système MariaDB ne sont pas encore à niveau. Les déclencheurs sont inclus.
# Usage : ./scripts/backup-stock-local.sh [répertoire de sauvegardes]
# MYSQL_DEFAULTS_FILE peut désigner un fichier privé contenant [client] et
# password=... ; le mot de passe ne doit jamais être passé dans les arguments.

if [[ $# -gt 1 ]]; then
    printf 'Usage : %s [répertoire de sauvegardes]\n' "$0" >&2
    exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
backup_root=${1:-"$script_dir/../.local-backups"}
dump_bin=${MYSQLDUMP_BIN:-/Applications/XAMPP/xamppfiles/bin/mysqldump}

if [[ ! -x "$dump_bin" ]]; then
    printf 'Le programme mysqldump est introuvable ou non exécutable.\n' >&2
    exit 1
fi
if ! command -v shasum >/dev/null 2>&1; then
    printf 'Le programme shasum est nécessaire pour vérifier la sauvegarde.\n' >&2
    exit 1
fi

defaults=(--no-defaults)
if [[ -n ${MYSQL_DEFAULTS_FILE:-} ]]; then
    if [[ ! -f "$MYSQL_DEFAULTS_FILE" || ! -r "$MYSQL_DEFAULTS_FILE" ]]; then
        printf 'MYSQL_DEFAULTS_FILE doit désigner un fichier lisible.\n' >&2
        exit 1
    fi
    # stat BSD sur macOS, puis GNU sur Linux. Ne pas lire le fichier secret.
    if permissions=$(stat -f '%Lp' "$MYSQL_DEFAULTS_FILE" 2>/dev/null); then
        owner=$(stat -f '%u' "$MYSQL_DEFAULTS_FILE")
    else
        permissions=$(stat -c '%a' "$MYSQL_DEFAULTS_FILE")
        owner=$(stat -c '%u' "$MYSQL_DEFAULTS_FILE")
    fi
    if [[ ! "$permissions" =~ ^[0-7]{3,4}$ ]] \
        || (( (8#$permissions & 077) != 0 )) \
        || [[ "$owner" != "$(id -u)" ]]; then
        printf 'Le fichier d’options doit vous appartenir et être privé (chmod 600).\n' >&2
        exit 1
    fi
    # Cette option doit être la première ; elle remplace --no-defaults.
    defaults=("--defaults-file=$MYSQL_DEFAULTS_FILE")
fi

mkdir -p -- "$backup_root"
backup_root=$(cd -- "$backup_root" && pwd -P)
backup_dir=$(mktemp -d "$backup_root/stock_pro-$(date +%Y%m%d-%H%M%S)-XXXXXX")
partial_file="$backup_dir/stock_pro.sql.partial"
dump_file="$backup_dir/stock_pro.sql"
checksum_partial="$backup_dir/SHA256SUMS.partial"
checksum_file="$backup_dir/SHA256SUMS"
completed=false

cleanup() {
    if [[ "$completed" != true ]]; then
        rm -f -- "$partial_file" "$dump_file" "$checksum_partial" "$checksum_file"
        rmdir -- "$backup_dir" 2>/dev/null || true
        printf 'La sauvegarde a échoué ; aucun dump incomplet n’a été conservé.\n' >&2
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$dump_bin" "${defaults[@]}" \
    --protocol=TCP --host=127.0.0.1 --port=3306 --user=root \
    --single-transaction --quick --hex-blob --triggers \
    --skip-routines --skip-events --default-character-set=utf8mb4 \
    stock_pro > "$partial_file"

if [[ ! -s "$partial_file" ]]; then
    printf 'mysqldump a produit un fichier vide.\n' >&2
    exit 1
fi

checksum=$(shasum -a 256 "$partial_file")
checksum=${checksum%% *}
if [[ ! "$checksum" =~ ^[0-9a-fA-F]{64}$ ]]; then
    printf 'Impossible de calculer une empreinte SHA-256 valide.\n' >&2
    exit 1
fi
printf '%s  stock_pro.sql\n' "$checksum" > "$checksum_partial"
mv -- "$partial_file" "$dump_file"
mv -- "$checksum_partial" "$checksum_file"
completed=true
printf 'Sauvegarde privée créée : %s\n' "$dump_file"
printf 'Empreinte SHA-256 : %s\n' "$checksum_file"
