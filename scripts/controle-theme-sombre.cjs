// Contrôle du thème sombre : contraste WCAG (règle color-contrast d'axe-core) et blocs clairs oubliés, à 375 et 1280 px.
// Sert les pages HTML déjà rendues (<htmlDir>/<page>.html, produites par MockMvc) et le dossier static de l'application ;
// les bibliothèques du CDN (Bootstrap JS, simple-datatables, Font Awesome) sont lues dans un dossier local (npm pack).
// Usage : node scripts/controle-theme-sombre.cjs <staticDir> <htmlDir> <dark|light> [pages,séparées,par,des,virgules] [375,1280]
// Variables : PLAYWRIGHT_PATH (défaut « playwright »), AXE_CORE_DIR (dossier du paquet axe-core),
//             VENDOR_DIR (contient bootstrap-5.2.3/package, simple-datatables-7.1.2/package, fortawesome-fontawesome-free-6.3.0/package).
// Code de sortie 1 s'il reste une violation de contraste.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const [staticDir, htmlDir, mode, pagesArg, widthsArg] = process.argv.slice(2);
const V = process.env.VENDOR_DIR || './vendor';
const axeSrc = fs.readFileSync(path.join(process.env.AXE_CORE_DIR || require.resolve('axe-core/package.json').replace(/package\.json$/, ''), 'axe.min.js'), 'utf8');
const cdn = {
  'cdn.jsdelivr.net/npm/bootstrap@5.2.3/dist/js/bootstrap.bundle.min.js': V + '/bootstrap-5.2.3/package/dist/js/bootstrap.bundle.min.js',
  'cdn.jsdelivr.net/npm/simple-datatables@7.1.2/dist/style.min.css': V + '/simple-datatables-7.1.2/package/dist/style.css',
  'cdn.jsdelivr.net/npm/simple-datatables@7.1.2/dist/umd/simple-datatables.min.js': V + '/simple-datatables-7.1.2/package/dist/umd/simple-datatables.js',
  'use.fontawesome.com/releases/v6.3.0/js/all.js': V + '/fortawesome-fontawesome-free-6.3.0/package/js/all.min.js',
};
const types = { '.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.png': 'image/png', '.html': 'text/html; charset=utf-8' };
const srv = http.createServer((req, res) => {
  const u = decodeURIComponent(req.url.split('?')[0]);
  const f = u.startsWith('/__pages/') ? path.join(htmlDir, u.slice(9)) : path.join(staticDir, u);
  fs.readFile(f, (e, d) => { if (e) { res.writeHead(404); res.end(); } else { res.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); res.end(d); } });
});
(async () => {
  await new Promise(r => srv.listen(0, r)); const port = srv.address().port;
  const pages = (pagesArg || 'tableau-de-bord,ventes,clients,journal,comptes,financements,rapprochement,pointage,comptage,comptages,nouvelle-vente,login,accueil,produits,etats,mon-compte,inscription,mdp-oublie,fournisseurs,factures,abonnement,parametres,depense,devis,achats,emprunt-nouveau').split(',');
  const widths = (widthsArg || '375,1280').split(',').map(Number);
  const browser = await chromium.launch(); let total = 0, minRatio = 99;
  for (const w of widths) {
    const ctx = await browser.newContext({ viewport: { width: w, height: 900 }, colorScheme: mode });
    await ctx.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => { const u = route.request().url().replace(/^https?:\/\//, ''); return cdn[u] ? route.fulfill({ path: cdn[u], contentType: types[path.extname(cdn[u])] }) : route.abort(); });
    await ctx.addInitScript(m => { try { localStorage.setItem('rfc-theme', m); } catch (e) {} }, mode === 'dark' ? 'dark' : 'light');
    for (const p of pages) {
      const page = await ctx.newPage();
      await page.goto(`http://127.0.0.1:${port}/__pages/${p}.html`, { waitUntil: 'load' }); await page.waitForTimeout(400);
      await page.evaluate(axeSrc);
      const r = await page.evaluate(async () => {
        const res = await axe.run(document, { runOnly: ['color-contrast'] });
        const bad = res.violations.flatMap(v => v.nodes.map(n => ({ sel: n.target.join(' '), d: (n.any[0] || {}).data || {}, html: n.html.slice(0, 90) })));
        // blocs clairs oubliés : fond opaque très clair sur un thème sombre
        const clairs = [];
        if (document.documentElement.getAttribute('data-bs-theme') === 'dark') {
          for (const el of document.querySelectorAll('body *')) {
            const cs = getComputedStyle(el); const m = cs.backgroundColor.match(/rgba?\((\d+), (\d+), (\d+)(?:, ([\d.]+))?\)/);
            if (!m || (m[4] !== undefined && +m[4] < 0.9)) continue;
            const r = el.getBoundingClientRect(); if (r.width < 8 || r.height < 8 || cs.visibility === 'hidden' || cs.display === 'none') continue;
            if ((+m[1] + +m[2] + +m[3]) / 3 > 170) clairs.push(el.tagName + '.' + String(el.className).slice(0, 50) + ' ' + cs.backgroundColor);
          }
        }
        return { theme: document.documentElement.getAttribute('data-bs-theme'), bad, clairs: clairs.slice(0, 8) };
      });
      total += r.bad.length;
      for (const b of r.bad) if (b.d.contrastRatio) minRatio = Math.min(minRatio, b.d.contrastRatio);
      console.log(`${p}@${w} [${r.theme}] violations=${r.bad.length} clairs=${r.clairs.length}`);
      for (const b of r.bad.slice(0, 6)) console.log('   ', b.d.contrastRatio, b.d.fgColor, 'sur', b.d.bgColor, b.sel.slice(0, 80), '|', b.html.replace(/\s+/g, ' '));
      for (const c of r.clairs) console.log('    CLAIR', c);
      await page.close();
    }
    await ctx.close();
  }
  console.log('TOTAL violations', total, 'ratio min', minRatio);
  await browser.close(); srv.close();
  process.exit(total ? 1 : 0);
})();
