package org.example.stock.form;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Dates et capitaux des échéances d'un emprunt, dans l'ordre des rangs. Le nombre d'échéances ne change pas. */
@Getter @Setter
public class EcheancierForm {
    @Size(max = 120)
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) private List<LocalDate> dates = new ArrayList<>();
    @Size(max = 120)
    private List<BigDecimal> capitaux = new ArrayList<>();
}
