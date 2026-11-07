// Calculs monétaires en centimes entiers, sans conversion en Number.
const Montants = Object.freeze({
    maximum: 99999999999999999n,
    lire(valeur) {
        const texte = String(valeur).trim();
        const match = /^(-?)(\d{1,15})(?:\.(\d{1,2}))?$/.exec(texte);
        if (!match) return null;
        const centimes = BigInt(match[2]) * 100n + BigInt((match[3] || '').padEnd(2, '0'));
        return match[1] ? -centimes : centimes;
    },
    formater(centimes) {
        const signe = centimes < 0n ? '-' : '';
        const valeur = centimes < 0n ? -centimes : centimes;
        return `${signe}${valeur / 100n}.${String(valeur % 100n).padStart(2, '0')}`;
    },
    /** Part du reste couverte par l'avance du partenaire : min(avance, max(0, total − versé)). */
    avanceUtilisee(total, verse, avance) {
        const du = total - verse;
        return du <= 0n ? 0n : (avance < du ? avance : du);
    },
    afficher(centimes) {
        const [entier, fraction] = this.formater(centimes).split('.');
        return `${entier.replace(/\B(?=(\d{3})+(?!\d))/g, ' ')}.${fraction}`;
    }
});
