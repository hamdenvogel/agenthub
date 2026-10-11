package br.com.hvogel.agenthub.api;

import br.com.hvogel.agenthub.schema.SchemaCatalog;
import br.com.hvogel.agenthub.schema.SchemaCatalog.Catalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inspeção somente-leitura, sem custo de LLM, dos JSON Schemas usados nas chamadas ao Claude.
 */
@RestController
@RequestMapping("/api")
public class SchemaController {

    private final SchemaCatalog catalog;

    public SchemaController(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/schemas")
    public Catalog schemas() {
        return catalog.catalog();
    }
}
