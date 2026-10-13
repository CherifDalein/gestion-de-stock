package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.form.*;
import org.example.stock.service.InventaireService;
import org.example.stock.service.ProduitModifieException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Arrays;

@Controller
@RequestMapping("/produits/{id}/stock")
public class InventaireController {
    private final InventaireService inventaire;
    public InventaireController(InventaireService inventaire) { this.inventaire=inventaire; }
    @InitBinder({"stockInitial", "ajustementStock"})
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if(binder instanceof ExtendedServletRequestDataBinder servlet) servlet.addHeaderPredicate(nom->false);
        String[] champs=binder.getObjectName().equals("stockInitial")
                ?new String[]{"quantite","coutUnitaire","prixVente","motif","version","_csrf"}
                :new String[]{"quantite","motif","version","_csrf"};
        boolean soumis=request.getRequestURI().endsWith(binder.getObjectName().equals("stockInitial")?"/initial":"/ajuster");
        if(request.getMethod().equals("POST")&&soumis) {
            for(var param:request.getParameterMap().entrySet()) {
                if(Arrays.stream(champs).noneMatch(c->c.equals(param.getKey()))||param.getValue().length!=1)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Champ de formulaire non autorisé");
            }
        }
        binder.setAllowedFields(java.util.stream.Stream.concat(Arrays.stream(champs),java.util.stream.Stream.of("id")).toArray(String[]::new));
    }
    @GetMapping
    public String afficher(@PathVariable Long id, @RequestParam(defaultValue="0") int page, Model model) {
        return remplir(id,page,model);
    }
    private String remplir(Long id, int page, Model model) {
        var vue=inventaire.consulter(id,page);model.addAttribute("inventaire",vue);
        if(!model.containsAttribute("stockInitial")) {
            var form=new StockInitialForm();form.setVersion(vue.produit().getVersion());form.setMotif("Marchandises déjà présentes au démarrage");model.addAttribute("stockInitial",form);
        }
        if(!model.containsAttribute("ajustementStock")) {
            var form=new AjustementStockForm();form.setVersion(vue.produit().getVersion());form.setQuantite(vue.produit().getQuantite());model.addAttribute("ajustementStock",form);
        }
        model.addAttribute("view","produits/stock");return "dashboard";
    }
    @PostMapping("/initial")
    public String initialiser(@PathVariable Long id,@Valid @ModelAttribute("stockInitial") StockInitialForm form, BindingResult erreurs,
                             Model model, HttpServletResponse response, RedirectAttributes redirect) {
        FormBindingAdvice.verifier(erreurs);
        if(!erreurs.hasErrors()) {
            try {inventaire.initialiser(id,form);redirect.addFlashAttribute("success","Stock initial enregistré, sans mouvement de caisse.");return "redirect:/produits/"+id+"/stock";}
            catch(ProduitModifieException|ConcurrencyFailureException e) { response.setStatus(409);model.addAttribute("error","Le stock a changé. Rechargez cette page avant de réessayer."); }
            catch(IllegalArgumentException e) { model.addAttribute("error",e.getMessage()); }
        }
        return remplir(id,0,model);
    }
    @PostMapping("/ajuster")
    public String ajuster(@PathVariable Long id,@Valid @ModelAttribute("ajustementStock") AjustementStockForm form, BindingResult erreurs,
                         Model model, HttpServletResponse response, RedirectAttributes redirect) {
        FormBindingAdvice.verifier(erreurs);
        if(!erreurs.hasErrors()) {
            try {inventaire.ajuster(id,form);redirect.addFlashAttribute("success","Ajustement du stock enregistré dans l’historique.");return "redirect:/produits/"+id+"/stock";}
            catch(ProduitModifieException|ConcurrencyFailureException e) { response.setStatus(409);model.addAttribute("error","Le stock a changé. Rechargez cette page avant de réessayer."); }
            catch(IllegalArgumentException e) { model.addAttribute("error",e.getMessage()); }
        }
        return remplir(id,0,model);
    }
}
