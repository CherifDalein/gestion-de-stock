package org.example.stock.form;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter
@Setter
public class ProlongationDevisForm {
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateValidite;
}
