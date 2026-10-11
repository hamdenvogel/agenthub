package br.com.hvogel.agenthub.api;

import br.com.hvogel.agenthub.target.TargetService;
import br.com.hvogel.agenthub.target.TargetService.BrowseResult;
import br.com.hvogel.agenthub.target.TargetService.TargetInput;
import br.com.hvogel.agenthub.target.TargetService.TargetOverview;
import br.com.hvogel.agenthub.target.TargetService.TargetStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Projetos-alvo: cadastro (gravado em arquivo JSON), escolha do projeto ativo e navegação
 * pelas pastas permitidas.
 */
@RestController
@RequestMapping("/api/targets")
public class TargetController {

    private final TargetService service;

    public TargetController(TargetService service) {
        this.service = service;
    }

    @GetMapping
    public TargetOverview list() {
        return service.overview();
    }

    @GetMapping("/{targetId}")
    public TargetStatus get(@PathVariable String targetId) {
        return service.get(targetId);
    }

    @PostMapping
    public ResponseEntity<TargetStatus> create(@RequestBody TargetInput input,
                                               @RequestParam(defaultValue = "false") boolean activate) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(input, activate));
    }

    @PutMapping("/{targetId}")
    public TargetStatus update(@PathVariable String targetId, @RequestBody TargetInput input) {
        return service.update(targetId, input);
    }

    @DeleteMapping("/{targetId}")
    public ResponseEntity<Void> delete(@PathVariable String targetId) {
        service.delete(targetId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{targetId}/activate")
    public TargetStatus activate(@PathVariable String targetId) {
        return service.activate(targetId);
    }

    @GetMapping("/browse")
    public BrowseResult browse(@RequestParam(required = false) String path) {
        return service.browse(path);
    }
}
